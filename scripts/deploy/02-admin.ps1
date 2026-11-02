<#
.SYNOPSIS
Phase 2 (admin machine): log in as admin, register a Minecraft server, create an enrollment token.
.DESCRIPTION
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
  [string]$CaFile
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not (Test-Path $PasswordFile)) { throw 'Cannot read password file' }
$python = (Get-Command python, python3 -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $python) { throw 'Python 3 is required' }

$client = if ($env:MODSYNC_CLIENT) { $env:MODSYNC_CLIENT } else { Join-Path $root 'modsync-client\build\install\modsync-client\bin\modsync-client.bat' }
if (-not (Test-Path $client)) {
  Write-Host '==> Building the client'
  Push-Location $root; try { & .\gradlew.bat :modsync-client:installDist; if ($LASTEXITCODE) { throw 'build failed' } } finally { Pop-Location }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Client { & $client --state-dir $StateDir @args; if ($LASTEXITCODE) { throw "modsync failed: $args" } }
Write-Host '==> Pinning server and logging in as admin'
Invoke-Client setup --server-url $ServerUrl --trust-server-id $ControlPlaneId
Invoke-Client login --username admin --password-file $PasswordFile

function Invoke-Admin($Method, $Route, $Data, $Out, [string[]]$Extra = @()) {
  $a = @((Join-Path $root 'scripts\admin.py'), '--config', (Join-Path $StateDir 'config.json'), '--method', $Method, '--out', $Out)
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
