<#
.SYNOPSIS
Phase 3 (Host A): configure and start the scanning agent in Docker.
.DESCRIPTION
-ServersPath is the Crafty servers root (mounted read-only at /minecraft).
-ModsDir is relative to -ServersPath (use forward slashes, e.g. survival/mods).
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
  [ValidateRange(30, 86400)][int]$Interval = 300
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$dir = Join-Path $root 'docker\agent'
if (-not (Test-Path $TokenFile)) { throw 'Cannot read token file' }
if (-not (Test-Path $ServersPath)) { throw "Servers path not found: $ServersPath" }
if (-not (Test-Path (Join-Path $ServersPath $ModsDir))) { throw "Mods directory not found: $ServersPath\$ModsDir" }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required' }

New-Item -ItemType Directory -Force -Path $StatePath | Out-Null
$StatePath = (Resolve-Path $StatePath).Path
$ServersPath = (Resolve-Path $ServersPath).Path

Write-Host "==> Writing $dir\.env"
$envFile = Join-Path $dir '.env'
if (-not (Test-Path $envFile)) { Copy-Item (Join-Path $dir '.env.example') $envFile }
function Set-EnvValue($key, $value) {
  $lines = @(Get-Content $envFile); $found = $false
  $lines = $lines | ForEach-Object { if ($_ -match "^$key=") { $found = $true; "$key=$value" } else { $_ } }
  if (-not $found) { $lines += "$key=$value" }
  Set-Content -Path $envFile -Value $lines
}
Set-EnvValue 'MODSYNC_STATE_PATH' ($StatePath -replace '\\', '/')
Set-EnvValue 'MINECRAFT_SERVERS_PATH' ($ServersPath -replace '\\', '/')
Set-EnvValue 'MODSYNC_SCAN_INTERVAL' $Interval
Set-EnvValue 'MODSYNC_SERVER_URL' $ServerUrl

Push-Location $dir
function Invoke-Compose { docker compose --env-file .env @args; if ($LASTEXITCODE) { throw "docker compose failed: $args" } }
try {
  Write-Host '==> Building the agent image'; Invoke-Compose build
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
