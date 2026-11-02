<#
.SYNOPSIS
Phase 1 (Host B): create secrets, write .env, start the API (+ bundled MariaDB), verify health.
.DESCRIPTION
By default the bundled MariaDB container is used. With -DbHost (or -DbMode external) an existing MariaDB
is used instead: the database and user must already exist, nothing is created for you.
The DB password comes from -DbPasswordFile, an existing secrets\db_password, or a secure prompt.
Secrets are never printed; existing files are kept.
.PARAMETER SecretsDir  Directory for generated secrets (default <repo>\.local\secrets).
.PARAMETER Bind        Listen address (default 127.0.0.1).
.PARAMETER Url         Base URL used for health checks (default http://127.0.0.1:8080).
.PARAMETER Maintenance 'true' or 'false'.
.PARAMETER DbMode      'bundled' (default) or 'external'.
.PARAMETER DbHost      External MariaDB host (implies -DbMode external).
.PARAMETER DbPort      External MariaDB port (default 3306).
.PARAMETER DbName      Database name (default modsync).
.PARAMETER DbUser      Database user (default modsync).
.PARAMETER DbPasswordFile  File holding the DB password.
.PARAMETER DbSslMode   disable | trust | verify-ca | verify-full (default: disable for local hosts, else verify-full).
.PARAMETER DbUrl       Full JDBC URL override (jdbc:mariadb://...). Also pass -DbHost/-DbPort/-DbName for the check, or -SkipDbCheck.
.PARAMETER SkipDbCheck Skip the pre-flight connection check.
.EXAMPLE
.\01-server.ps1 -DbHost db.internal.example -DbPasswordFile C:\secrets\modsync-db
#>
[CmdletBinding()]
param(
  [string]$SecretsDir,
  [string]$Bind = '127.0.0.1',
  [string]$Url = 'http://127.0.0.1:8080',
  [ValidateSet('true', 'false')][string]$Maintenance = 'false',
  [ValidateSet('bundled', 'external')][string]$DbMode = 'bundled',
  [string]$DbHost,
  [ValidateRange(1, 65535)][int]$DbPort = 3306,
  [ValidatePattern('^[A-Za-z0-9_]+$')][string]$DbName = 'modsync',
  [ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$DbUser = 'modsync',
  [string]$DbPasswordFile,
  [ValidateSet('', 'disable', 'trust', 'verify-ca', 'verify-full')][string]$DbSslMode = '',
  [string]$DbUrl,
  [switch]$SkipDbCheck
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$srv = Join-Path $root 'docker\server'
if (-not $SecretsDir) { $SecretsDir = Join-Path $root '.local\secrets' }
$Url = $Url.TrimEnd('/')
if ($DbHost -or $DbUrl) { $DbMode = 'external' }
$external = $DbMode -eq 'external'
if ($external -and -not $DbHost -and -not $DbUrl) { throw 'External mode needs -DbHost (or -DbUrl)' }
if ($DbHost -and $DbHost -notmatch '^[A-Za-z0-9._:-]+$') { throw '-DbHost contains invalid characters' }
if ($DbUrl -and $DbUrl -notlike 'jdbc:mariadb://*') { throw '-DbUrl must start with jdbc:mariadb://' }
if ($DbUrl -and -not $DbHost -and -not $SkipDbCheck) { throw 'With -DbUrl also pass -DbHost/-DbPort/-DbName for the pre-flight check, or use -SkipDbCheck' }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required' }
docker compose version | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required' }
if ($external) {
  $cv = [version](((docker compose version --short) -replace '^v', '') -replace '[^0-9.].*$', '')
  if ($cv -lt [version]'2.24') { throw "External mode needs Docker Compose >= 2.24 (found $cv)" }
}

function Write-Secret($path, $text) { [System.IO.File]::WriteAllText($path, $text) }

Write-Host "==> Preparing secrets in $SecretsDir"
New-Item -ItemType Directory -Force -Path $SecretsDir | Out-Null
$SecretsDir = (Resolve-Path $SecretsDir).Path
function New-RandomSecret($name) {
  $file = Join-Path $SecretsDir $name
  if ((Test-Path $file) -and (Get-Item $file).Length -gt 0) { Write-Host "    keeping existing $name"; return }
  $bytes = New-Object byte[] 32
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
  Write-Secret $file (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
  Write-Host "    created $name"
}
New-RandomSecret 'bootstrap_password'
$dbPwPath = Join-Path $SecretsDir 'db_password'
if (-not $external) {
  New-RandomSecret 'db_password'; New-RandomSecret 'db_root_password'
} elseif ($DbPasswordFile) {
  if (-not (Test-Path $DbPasswordFile) -or (Get-Item $DbPasswordFile).Length -eq 0) { throw 'Cannot read -DbPasswordFile' }
  Write-Secret $dbPwPath ((Get-Content $DbPasswordFile -Raw) -replace '[\r\n]', '')
  Write-Host '    stored db_password from file'
} elseif ((Test-Path $dbPwPath) -and (Get-Item $dbPwPath).Length -gt 0) {
  Write-Host '    keeping existing db_password'
} elseif ([Environment]::UserInteractive) {
  $secure = Read-Host "MariaDB password for user '$DbUser'" -AsSecureString
  $plain = [System.Net.NetworkCredential]::new('', $secure).Password
  if (-not $plain) { throw 'Empty password' }
  Write-Secret $dbPwPath $plain; $plain = $null
  Write-Host '    stored db_password'
} else { throw 'Provide -DbPasswordFile (no terminal for a prompt)' }
Write-Host '    NOTE: Docker Desktop mounts these files; the container user (UID 10001) must be able to read them.'

$isLocal = $DbHost -in 'localhost', '127.0.0.1', '::1'
# A container cannot reach the host's loopback; use the Docker host alias instead.
$connectHost = if ($isLocal) { 'host.docker.internal' } else { $DbHost }
if (-not $DbSslMode) { $DbSslMode = if ($isLocal) { 'disable' } else { 'verify-full' } }
if (-not $DbUrl) { $DbUrl = "jdbc:mariadb://${connectHost}:${DbPort}/${DbName}?sslMode=$DbSslMode" }

if ($external) {
  Write-Host "`n==> External MariaDB (${connectHost}:${DbPort}/$DbName as $DbUser, sslMode=$DbSslMode)"
  Write-Host '    The DBA must have run (use a real password):'
  Write-Host "      CREATE DATABASE $DbName CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;"
  Write-Host "      CREATE USER '$DbUser'@'%' IDENTIFIED BY '<password>';"
  Write-Host "      GRANT ALL PRIVILEGES ON $DbName.* TO '$DbUser'@'%';"
  if ($SkipDbCheck) { Write-Host '    pre-flight check skipped' }
  else {
    Write-Host '==> Pre-flight check (throwaway mariadb:11.4.10 client container)'
    $ssl = switch ($DbSslMode) { 'disable' { '--skip-ssl' } 'trust' { '--ssl' } default { '--ssl --ssl-verify-server-cert' } }
    # SQL is piped on stdin so the shell snippet needs no quoting.
    $probe = "SELECT VERSION(); CREATE TABLE modsync_preflight_probe (id INT) ENGINE=InnoDB; DROP TABLE modsync_preflight_probe;"
    $sh = "MYSQL_PWD=`$(cat /pw) exec mariadb -h`$H -P`$P -u`$U `$N -N -B $ssl"
    $out = $probe | docker run --rm -i --add-host host.docker.internal:host-gateway `
      -v "${dbPwPath}:/pw:ro" -e "H=$connectHost" -e "P=$DbPort" -e "U=$DbUser" -e "N=$DbName" `
      mariadb:11.4.10 sh -c $sh 2>&1
    if ($LASTEXITCODE -ne 0) {
      Write-Error "Pre-flight failed:`n$out`nCheck host, port, credentials, TLS (-DbSslMode), firewall and that the user may CREATE/DROP tables in $DbName." -ErrorAction Continue
      exit 1
    }
    $version = @($out)[0]
    Write-Host "    login and CREATE/DROP privileges OK (server $version)"
    if ($version -notlike '11.4.*') { Write-Warning "ModSync is tested with MariaDB 11.4.x; found $version" }
  }
}

Write-Host "==> Writing $srv\.env"
$envFile = Join-Path $srv '.env'
if (-not (Test-Path $envFile)) { Copy-Item (Join-Path $srv '.env.example') $envFile }
function Set-EnvValue($key, $value) {
  $lines = @(Get-Content $envFile | Where-Object { $_ -notmatch "^$([regex]::Escape($key))=" })
  Set-Content -Path $envFile -Value ($lines + "$key=$value")
}
# Compose accepts forward slashes in Windows paths.
Set-EnvValue 'MODSYNC_SECRETS_DIR' ($SecretsDir -replace '\\', '/')
Set-EnvValue 'MODSYNC_BIND_ADDRESS' $Bind
Set-EnvValue 'MODSYNC_MAINTENANCE' $Maintenance
if ($external) { Set-EnvValue 'MODSYNC_DB_URL' $DbUrl; Set-EnvValue 'MODSYNC_DB_USER' $DbUser }

Write-Host "==> Starting containers ($DbMode database)"
Push-Location $srv
try {
  $files = @('-f', 'compose.yaml'); $services = @()
  if ($external) { $files += '-f', 'compose.external-db.yaml'; $services = @('modsync-server') }
  docker compose --env-file .env @files up -d --build @services
  if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
  docker compose --env-file .env @files ps
} finally { Pop-Location }

Write-Host "==> Waiting for health at $Url/actuator/health"
$ok = $false
for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
  try { Invoke-RestMethod "$Url/actuator/health" | Out-Null; $ok = $true } catch { Start-Sleep -Seconds 3 }
}
if (-not $ok) { throw 'Server did not become healthy. Check: docker compose logs modsync-server' }

Write-Host '==> /api/v1/info'
Invoke-RestMethod "$Url/api/v1/info" | ConvertTo-Json
Write-Host "`nDone. Record controlPlaneId (pinned by every client and agent)."
Write-Host "Admin password file: $SecretsDir\bootstrap_password (user: admin)."
if ($external) { Write-Host 'Backups: scripts/backup/*.sh target the bundled DB; back up the external database with mariadb-dump.' }
Write-Host 'Next: put a TLS reverse proxy in front of 127.0.0.1:8080, then run 02-admin.'
