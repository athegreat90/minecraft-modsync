<#
.SYNOPSIS
Phase 4 (player machine): build/install the client, pin the server, log in, register an instance.
.DESCRIPTION
Without -InstancePath the script tries 'instances discover'.
-AddToPath adds the launcher directory to the user PATH (opt-in).
.EXAMPLE
.\04-client.ps1 -ServerUrl https://modsync.example.org -ControlPlaneId UUID -Username alex -InstanceName survival -InstancePath "$env:APPDATA\.minecraft" -MinecraftVersion 1.21.1 -Loader neoforge
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$ServerUrl,
  [Parameter(Mandatory)][string]$ControlPlaneId,
  [Parameter(Mandatory)][string]$Username,
  [string]$PasswordFile,
  [string]$InstanceName,
  [string]$InstancePath,
  [string]$MinecraftVersion,
  [string]$Loader,
  [string]$LoaderVersion,
  [string]$StateDir,
  [switch]$AddToPath
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ($InstancePath) {
  if (-not ($InstanceName -and $MinecraftVersion -and $Loader)) { throw '-InstancePath needs -InstanceName, -MinecraftVersion and -Loader' }
  if (-not (Test-Path $InstancePath)) { throw "Instance path not found: $InstancePath" }
}

Write-Host '==> Checking Java'
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 25 is required (java not found)' }
$first = (& java -version 2>&1 | Select-Object -First 1).ToString()
if ($first -notmatch 'version "(\d+)') { throw "Cannot parse Java version: $first" }
if ([int]$Matches[1] -lt 25) { throw "Java 25+ required, found $($Matches[1])" }

$binDir = Join-Path $root 'modsync-client\build\install\modsync-client\bin'
$launcher = Join-Path $binDir 'modsync-client.bat'
if (-not (Test-Path $launcher)) {
  Write-Host '==> Building the client'
  Push-Location $root; try { & .\gradlew.bat :modsync-client:installDist; if ($LASTEXITCODE) { throw 'build failed' } } finally { Pop-Location }
}
$sf = @(); if ($StateDir) { $sf = @('--state-dir', $StateDir) }
function Invoke-Client { & $launcher @sf @args; if ($LASTEXITCODE) { throw "modsync failed: $args" } }

if ($AddToPath) {
  $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
  if (($userPath -split ';') -notcontains $binDir) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$binDir", 'User')
    Write-Host "==> Added $binDir to the user PATH (open a new terminal to use 'modsync-client')."
  }
}

Write-Host '==> Pinning the server'
Invoke-Client setup --server-url $ServerUrl --trust-server-id $ControlPlaneId
Write-Host '==> Logging in'
if ($PasswordFile) { Invoke-Client login --username $Username --password-file $PasswordFile } else { Invoke-Client login --username $Username }
Write-Host '==> Testing the connection'
Invoke-Client connection test
Invoke-Client servers list

if ($InstancePath) {
  Write-Host "==> Registering instance '$InstanceName'"
  $extra = @(); if ($LoaderVersion) { $extra = @('--loader-version', $LoaderVersion) }
  Invoke-Client instances add $InstanceName $InstancePath --minecraft-version $MinecraftVersion --loader $Loader @extra
} else {
  Write-Host '==> Discovering instances'
  & $launcher @sf instances discover
}
Write-Host "`nDone. Next: close Minecraft, then:"
Write-Host '  modsync-client sync plan --instance NAME --server SERVER_UUID --out plan.json'
Write-Host '  modsync-client sync apply --instance NAME --plan plan.json'
