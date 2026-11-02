<#
.SYNOPSIS
Phase 3 (Host A): configure and start the scanning agent in Docker.
.DESCRIPTION
-ServersPath is the Crafty servers root (mounted read-only at /minecraft).
-ModsDir is relative to -ServersPath (use forward slashes, e.g. survival/mods).
-Version (default: latest GitHub release) selects the GHCR image; -Repo and -WorkDir (default .\modsync-deploy) are optional.
The repository is not needed: compose.yml is downloaded from GitHub when not found next to this script.
-ServerUuid triggers a first scan. The token file is copied into the state dir, then deleted.
.EXAMPLE
.\03-agent.ps1 -ServerUrl https://modsync.example.org -ControlPlaneId UUID -TokenFile .\enrollment-token -StatePath D:\modsync\agent -ServersPath D:\crafty\servers -Alias survival -ModsDir survival/mods
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$ServerUrl,
  [Parameter(Mandatory)][string]$ControlPlaneId,
  [Parameter(Mandatory)][string]$TokenFile,
  [Parameter(Mandatory)][string]$StatePath,
  [Parameter(Mandatory)][string]$ServersPath,
  [Parameter(Mandatory)][string]$Alias,
  [Parameter(Mandatory)][string]$ModsDir,
  [string]$ServerUuid,
  [string]$Version,
  [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repo = 'athegreat90/minecraft-modsync',
  [string]$WorkDir = (Join-Path (Get-Location).Path 'modsync-deploy'),
  [ValidateRange(30, 86400)][int]$Interval = 300
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $TokenFile)) { throw 'Cannot read token file' }
if (-not (Test-Path $ServersPath)) { throw "Servers path not found: $ServersPath" }
if (-not (Test-Path (Join-Path $ServersPath $ModsDir))) { throw "Mods directory not found: $ServersPath\$ModsDir" }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required' }

$dir = Join-Path $WorkDir 'agent'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
if (-not $Version) {
  $Version = (& { try { (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest").tag_name } catch { (@(Invoke-RestMethod "https://api.github.com/repos/$Repo/releases?per_page=1"))[0].tag_name } })
  if (-not $Version) { throw "Cannot resolve the latest release of $Repo; pass -Version TAG" }
}
Write-Host "==> Using $Repo release $Version"
$local = Join-Path $PSScriptRoot 'agent\compose.yml'
if (Test-Path $local) { Copy-Item $local (Join-Path $dir 'compose.yml') -Force }
else {
  try { Invoke-WebRequest "https://raw.githubusercontent.com/$Repo/$Version/scripts/deploy/agent/compose.yml" -OutFile (Join-Path $dir 'compose.yml') -UseBasicParsing }
  catch { Invoke-WebRequest "https://raw.githubusercontent.com/$Repo/main/scripts/deploy/agent/compose.yml" -OutFile (Join-Path $dir 'compose.yml') -UseBasicParsing }
}

New-Item -ItemType Directory -Force -Path $StatePath | Out-Null
$StatePath = (Resolve-Path $StatePath).Path
$ServersPath = (Resolve-Path $ServersPath).Path

Write-Host "==> Writing $dir\.env"
$envFile = Join-Path $dir '.env'
if (-not (Test-Path $envFile)) { New-Item -ItemType File -Path $envFile | Out-Null }
function Set-EnvValue($key, $value) {
  $lines = @(Get-Content $envFile); $found = $false
  $lines = $lines | ForEach-Object { if ($_ -match "^$key=") { $found = $true; "$key=$value" } else { $_ } }
  if (-not $found) { $lines += "$key=$value" }
  Set-Content -Path $envFile -Value $lines
}
Set-EnvValue 'MODSYNC_VERSION' $Version
Set-EnvValue 'MODSYNC_REPO' $Repo
Set-EnvValue 'MODSYNC_STATE_PATH' ($StatePath -replace '\\', '/')
Set-EnvValue 'MINECRAFT_SERVERS_PATH' ($ServersPath -replace '\\', '/')
Set-EnvValue 'MODSYNC_SCAN_INTERVAL' $Interval
Set-EnvValue 'MODSYNC_SERVER_URL' $ServerUrl

Push-Location $dir
function Invoke-Compose { docker compose --env-file .env @args; if ($LASTEXITCODE) { throw "docker compose failed: $args" } }
try {
  Write-Host '==> Pulling the agent image'; Invoke-Compose pull
  Write-Host '==> Pinning the server'
  Invoke-Compose run --rm agent setup --server-url $ServerUrl --trust-server-id $ControlPlaneId

  Write-Host '==> Registering with the enrollment token'
  $stateToken = Join-Path $StatePath 'enrollment-token'
  Copy-Item $TokenFile $stateToken -Force
  try { Invoke-Compose run --rm agent register --enrollment-token-file /app/state/enrollment-token }
  finally { Remove-Item $stateToken -Force -ErrorAction SilentlyContinue }
  Remove-Item $TokenFile -Force

  Write-Host "==> Mapping alias '$Alias'"
  Invoke-Compose run --rm agent map $Alias "/minecraft/$ModsDir"

  Write-Host '==> Agent status'
  docker compose --env-file .env run --rm agent status

  if ($ServerUuid) {
    Write-Host "==> First scan for $ServerUuid (requires the admin to have assigned this agent)"
    docker compose --env-file .env run --rm agent scan --server $ServerUuid
    if ($LASTEXITCODE) { Write-Warning 'Scan failed; assign the agent first (docs/USAGE.md 3.3) and rerun.' }
  }

  Write-Host '==> Starting the agent'
  Invoke-Compose up -d
} finally { Pop-Location }
Write-Host "`nDone. Report the agent id to the administrator (GET admin/agents) so it can be assigned with mapping '$Alias'."
