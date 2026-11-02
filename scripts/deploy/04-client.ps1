<#
.SYNOPSIS
Phase 4 (player machine): download the client, pin the server, log in, register an instance.
.DESCRIPTION
Downloads the native client from the GitHub release (-Version, default latest; -Repo; -WorkDir default .\modsync-deploy); the repository is not needed.
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
  [switch]$AddToPath,
  [string]$Version,
  [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repo = 'athegreat90/minecraft-modsync',
  [string]$WorkDir = (Join-Path (Get-Location).Path 'modsync-deploy')
)
$ErrorActionPreference = 'Stop'
if ($InstancePath) {
  if (-not ($InstanceName -and $MinecraftVersion -and $Loader)) { throw '-InstancePath needs -InstanceName, -MinecraftVersion and -Loader' }
  if (-not (Test-Path $InstancePath)) { throw "Instance path not found: $InstancePath" }
}

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
$launcher = Get-Client
$binDir = Split-Path $launcher
$sf = @(); if ($StateDir) { $sf = @('--state-dir', $StateDir) }
function Invoke-Client { & $launcher @sf @args; if ($LASTEXITCODE) { throw "modsync failed: $args" } }

if ($AddToPath) {
  $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
  if (($userPath -split ';') -notcontains $binDir) {
    [Environment]::SetEnvironmentVariable('Path', "$userPath;$binDir", 'User')
    Write-Host "==> Added $binDir to the user PATH (open a new terminal to use 'modsync')."
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
Write-Host '  modsync sync plan --instance NAME --server SERVER_UUID --out plan.json'
Write-Host '  modsync sync apply --instance NAME --plan plan.json'
