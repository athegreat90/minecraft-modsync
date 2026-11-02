<#
.SYNOPSIS
Phase 2 (admin machine): log in as admin, register a Minecraft server, create an enrollment token.
.DESCRIPTION
Downloads the client binary and admin.py from the GitHub release (-Version, default latest; -Repo; -WorkDir default .\modsync-deploy); the repository is not needed.
Outputs in -OutDir: server-response.json and enrollment-token (give only the token to the agent operator).
.EXAMPLE
.\02-admin.ps1 -ServerUrl https://modsync.example.org -ControlPlaneId UUID -PasswordFile C:\secrets\bootstrap_password -Name survival -MinecraftVersion 1.21.1
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$ServerUrl,
  [Parameter(Mandatory)][string]$ControlPlaneId,
  [Parameter(Mandatory)][string]$PasswordFile,
  [Parameter(Mandatory)][string]$Name,
  [Parameter(Mandatory)][string]$MinecraftVersion,
  [string]$Loader = 'neoforge',
  [string]$LoaderVersion,
  [string]$StateDir = (Join-Path $HOME '.modsync-admin'),
  [string]$OutDir = (Join-Path (Get-Location) 'admin-out'),
  [ValidateRange(60, 3600)][int]$Ttl = 600,
  [string]$CaFile,
  [string]$Version,
  [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repo = 'athegreat90/minecraft-modsync',
  [string]$WorkDir = (Join-Path (Get-Location).Path 'modsync-deploy')
)
$ErrorActionPreference = 'Stop'
if (-not $Version) {
  $Version = (& { try { (Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest").tag_name } catch { (@(Invoke-RestMethod "https://api.github.com/repos/$Repo/releases?per_page=1"))[0].tag_name } })
  if (-not $Version) { throw "Cannot resolve the latest release of $Repo; pass -Version TAG" }
}
Write-Host "==> Using $Repo release $Version"
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$WorkDir = (Resolve-Path $WorkDir).Path
$ProgressPreference = 'SilentlyContinue'
$base = "https://github.com/$Repo/releases/download/$Version"
function Get-Client {
  $bin = Join-Path $WorkDir 'bin'
  New-Item -ItemType Directory -Force -Path $bin | Out-Null
  $arch = $env:PROCESSOR_ARCHITECTURE
  if (-not ($IsLinux -or $IsMacOS) -and $arch -ne 'AMD64') { throw "No native Windows build for $arch; use the Java client zip from $base" }
  $asset = 'modsync-windows-x64.exe'
  $exe = Join-Path $bin "modsync-$Version-windows-x64.exe"
  if (-not (Test-Path $exe)) {
    Write-Host "==> Downloading the client ($asset)"
    $part = "$exe.part"
    Invoke-WebRequest "$base/$asset" -OutFile $part -UseBasicParsing
    $sums = Join-Path $bin 'SHA256SUMS.tmp'
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile $sums -UseBasicParsing
    $line = Get-Content $sums | Where-Object { ($_ -split '\s+')[1] -eq $asset } | Select-Object -First 1
    Remove-Item $sums -Force
    $want = if ($line) { ($line -split '\s+')[0] } else { '' }
    if (-not $want -or (Get-FileHash $part -Algorithm SHA256).Hash -ne $want.ToUpper()) { Remove-Item $part -Force; throw "Checksum mismatch or missing for $asset" }
    Move-Item $part $exe -Force
  }
  Copy-Item $exe (Join-Path $bin 'modsync.exe') -Force
  $exe
}
if (-not (Test-Path $PasswordFile)) { throw 'Cannot read password file' }
$python = (Get-Command python, python3 -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $python) { throw 'Python 3 is required' }

$client = if ($env:MODSYNC_CLIENT) { $env:MODSYNC_CLIENT } else { Get-Client }
$localAdmin = Join-Path $PSScriptRoot '..\admin.py'
if (Test-Path $localAdmin) { $adminPy = (Resolve-Path $localAdmin).Path }
else {
  $adminPy = Join-Path $WorkDir "admin-$Version.py"
  if (-not (Test-Path $adminPy)) { Invoke-WebRequest "https://raw.githubusercontent.com/$Repo/$Version/scripts/admin.py" -OutFile $adminPy -UseBasicParsing }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Client { & $client --state-dir $StateDir @args; if ($LASTEXITCODE) { throw "modsync failed: $args" } }
Write-Host '==> Pinning server and logging in as admin'
Invoke-Client setup --server-url $ServerUrl --trust-server-id $ControlPlaneId
Invoke-Client login --username admin --password-file $PasswordFile

function Invoke-Admin($Method, $Route, $Data, $Out, [string[]]$Extra = @()) {
  $a = @($adminPy, '--config', (Join-Path $StateDir 'config.json'), '--method', $Method, '--out', $Out)
  if ($Data) { $a += '--data', $Data }
  if ($CaFile) { $a += '--ca-file', $CaFile }
  & $python @a @Extra $Route
  if ($LASTEXITCODE) { throw "admin.py failed for $Route" }
}

Write-Host "==> Registering server '$Name'"
$body = [ordered]@{ name = $Name; minecraftVersion = $MinecraftVersion; loader = $Loader }
if ($LoaderVersion) { $body.loaderVersion = $LoaderVersion }
$serverReq = Join-Path $OutDir 'server-request.json'
$serverResp = Join-Path $OutDir 'server-response.json'
[System.IO.File]::WriteAllText($serverReq, ($body | ConvertTo-Json -Compress))
Invoke-Admin POST 'admin/servers' $serverReq $serverResp

Write-Host "==> Creating enrollment token (TTL ${Ttl}s)"
$enrollReq = Join-Path $OutDir 'enroll-request.json'
[System.IO.File]::WriteAllText($enrollReq, "{`"ttlSeconds`":$Ttl}")
$tokenFile = Join-Path $OutDir 'enrollment-token'
Invoke-Admin POST 'admin/enrollments' $enrollReq $tokenFile @('--secret-field', 'token')

$id = $null
try { $id = (Get-Content $serverResp -Raw | ConvertFrom-Json).id } catch {}
Write-Host "`nDone."
if ($id) { Write-Host "SERVER_UUID: $id" } else { Write-Host "Find SERVER_UUID in $serverResp" }
Write-Host "Enrollment token file: $tokenFile (expires in ${Ttl}s; single use)"
Write-Host 'Next: run 03-agent on Host A, then assign the agent (see docs/USAGE.md section 3.3).'
