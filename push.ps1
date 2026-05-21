# wBooks deploy script
# Usage: .\push.ps1 [-Watch] [-Phone] [-WatchPort <port>] [-PhonePort <port>]
# Example: .\push.ps1 -Watch -WatchPort 44217
#          .\push.ps1 -Phone -PhonePort 43837
#          .\push.ps1 -Watch -WatchPort 44217 -Phone -PhonePort 43837

param(
    [switch]$Watch,
    [switch]$Phone,
    [string]$WatchPort,
    [string]$PhonePort
)

$ErrorActionPreference = "Stop"
$root    = $PSScriptRoot
$wtTools = Join-Path $root "..\watchtalk\.tools"
$adb     = Join-Path $wtTools "android-sdk\platform-tools\adb.exe"

$watchIp = "192.168.50.143"
$phoneIp = "192.168.50.145"

$userName      = $env:USERNAME
$buildRoot     = "C:\GradleTmp\$userName\wbooks-build"
$watchApk      = "$buildRoot\app\outputs\apk\debug\app-debug.apk"
$companionApk  = "$buildRoot\companion\outputs\apk\debug\companion-debug.apk"

function Build-Modules($modules) {
    Write-Host "`n==> Building $($modules -join ', ')..." -ForegroundColor Cyan
    $tasks = $modules | ForEach-Object { ":${_}:assembleDebug" }
    & (Join-Path $root "gradlew.bat") @tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    Write-Host "==> Build succeeded" -ForegroundColor Green
}

function Push-Apk($apkPath, $target, $label) {
    Write-Host "`n==> Connecting to $label at $target..." -ForegroundColor Cyan
    & $adb connect $target | Out-Null
    Start-Sleep -Seconds 1
    Write-Host "==> Installing $label APK..." -ForegroundColor Cyan
    & $adb -s $target install -r $apkPath
    if ($LASTEXITCODE -ne 0) { throw "$label install failed" }
    Write-Host "==> $label installed successfully" -ForegroundColor Green
}

if (-not $Watch -and -not $Phone) {
    Write-Host "Specify one or more targets: -Watch [-WatchPort <port>]  -Phone [-PhonePort <port>]" -ForegroundColor Yellow
    exit 0
}

$modules = @()
if ($Watch)   { $modules += "app" }
if ($Phone)   { $modules += "companion" }
Build-Modules $modules

if ($Watch) {
    if (-not $WatchPort) { $WatchPort = Read-Host "Watch ADB port" }
    Push-Apk $watchApk "${watchIp}:${WatchPort}" "Watch"
}

if ($Phone) {
    if (-not $PhonePort) { $PhonePort = Read-Host "Phone ADB port" }
    Push-Apk $companionApk "${phoneIp}:${PhonePort}" "Phone"
}

Write-Host "`n==> Done." -ForegroundColor Green
