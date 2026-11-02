<#
.SYNOPSIS
Phase 1 (Host B): create secrets, write .env, start API + MariaDB, verify health.
.PARAMETER SecretsDir  Directory for generated secrets (default <repo>\.local\secrets).
.PARAMETER Bind        Listen address (default 127.0.0.1).
.PARAMETER Url         Base URL used for health checks (default http://127.0.0.1:8080).
.PARAMETER Maintenance 'true' or 'false'.
Secrets are generated once and never printed; existing files are kept.
#>
[CmdletBinding()]
param(
  [string]$SecretsDir,
  [string]$Bind = '127.0.0.1',
  [string]$Url = 'http://127.0.0.1:8080',
  [ValidateSet('true', 'false')][string]$Maintenance = 'false'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$srv = Join-Path $root 'docker\server'
if (-not $SecretsDir) { $SecretsDir = Join-Path $root '.local\secrets' }
$Url = $Url.TrimEnd('/')

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required' }
docker compose version | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required' }

Write-Host "==> Creating secrets in $SecretsDir"
New-Item -ItemType Directory -Force -Path $SecretsDir | Out-Null
$SecretsDir = (Resolve-Path $SecretsDir).Path
foreach ($name in 'db_password', 'db_root_password', 'bootstrap_password') {
  $file = Join-Path $SecretsDir $name
  if ((Test-Path $file) -and (Get-Item $file).Length -gt 0) { Write-Host "    keeping existing $name"; continue }
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
  [System.IO.File]::WriteAllText($file, $hex)
  Write-Host "    created $name"
}
Write-Host '    NOTE: Docker Desktop mounts these files; the container user (UID 10001) must be able to read them.'

Write-Host "==> Writing $srv\.env"
$envFile = Join-Path $srv '.env'
if (-not (Test-Path $envFile)) { Copy-Item (Join-Path $srv '.env.example') $envFile }
function Set-EnvValue($key, $value) {
  $lines = @(Get-Content $envFile)
  $found = $false
  $lines = $lines | ForEach-Object { if ($_ -match "^$key=") { $found = $true; "$key=$value" } else { $_ } }
  if (-not $found) { $lines += "$key=$value" }
  Set-Content -Path $envFile -Value $lines
}
# Compose accepts forward slashes in Windows paths.
Set-EnvValue 'MODSYNC_SECRETS_DIR' ($SecretsDir -replace '\\', '/')
Set-EnvValue 'MODSYNC_BIND_ADDRESS' $Bind
Set-EnvValue 'MODSYNC_MAINTENANCE' $Maintenance

Write-Host '==> Starting containers'
Push-Location $srv
try {
  docker compose --env-file .env -f compose.yaml up -d --build
  if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
  docker compose --env-file .env -f compose.yaml ps
} finally { Pop-Location }

Write-Host "==> Waiting for health at $Url/actuator/health"
$ok = $false
for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
  try { Invoke-RestMethod "$Url/actuator/health" | Out-Null; $ok = $true } catch { Start-Sleep -Seconds 3 }
}
if (-not $ok) { throw 'Server did not become healthy. Check: docker compose logs' }

Write-Host '==> /api/v1/info'
Invoke-RestMethod "$Url/api/v1/info" | ConvertTo-Json
Write-Host "`nDone. Record controlPlaneId (pinned by every client and agent)."
Write-Host "Admin password file: $SecretsDir\bootstrap_password (user: admin)."
Write-Host 'Next: put a TLS reverse proxy in front of 127.0.0.1:8080, then run 02-admin.'
