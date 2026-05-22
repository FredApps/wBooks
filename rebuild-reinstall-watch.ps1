# Rebuild and reinstall the wBooks watch app.
# Usage: .\rebuild-reinstall-watch.ps1
#        .\rebuild-reinstall-watch.ps1 -WatchSerial adb-RFAW81T9GVJ-NVBniB._adb-tls-connect._tcp
#        .\rebuild-reinstall-watch.ps1 -WatchSerial 10.238.16.48:5555

param(
    [string]$WatchSerial = "adb-RFAW81T9GVJ-NVBniB._adb-tls-connect._tcp",
    [string]$WatchIp = "10.238.16.48:5555",
    [switch]$SkipBuild,
    [switch]$SkipPull
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$tools = [Environment]::GetEnvironmentVariable("WATCHTALK_TOOLS", "User")
if ([string]::IsNullOrWhiteSpace($tools)) {
    $tools = "C:\Users\fha\OneDrive\Projects\WatchTalk\.tools"
}

$env:WATCHTALK_TOOLS = $tools
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "User")
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $env:JAVA_HOME = Join-Path $tools "jdk"
}
$env:ANDROID_HOME = [Environment]::GetEnvironmentVariable("ANDROID_HOME", "User")
if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $env:ANDROID_HOME = Join-Path $tools "android-sdk"
}
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = [Environment]::GetEnvironmentVariable("Path", "User") + ";" + [Environment]::GetEnvironmentVariable("Path", "Machine")

$adb = Join-Path $tools "android-sdk\platform-tools\adb.exe"
$gradle = Join-Path $root "gradlew.bat"
$buildRoot = "C:\GradleTmp\$env:USERNAME\wbooks-build"
$apk = Join-Path $buildRoot "app\outputs\apk\debug\app-debug.apk"
$remoteApk = "/data/local/tmp/wbooks-app-debug.apk"

function Assert-File($path, $label) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "$label not found: $path"
    }
}

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed"
    }
}

function Test-AdbDevice($serial) {
    $devices = & $adb devices
    return $devices -match ("^" + [regex]::Escape($serial) + "\s+device$")
}

function Resolve-WatchSerial {
    param([string]$PreferredSerial, [string]$PreferredIp)

    & $adb start-server | Out-Null

    if (Test-AdbDevice $PreferredSerial) {
        return $PreferredSerial
    }

    Write-Host "Watch serial not connected; trying $PreferredIp..." -ForegroundColor Yellow
    & $adb connect $PreferredIp | Out-Host
    Start-Sleep -Seconds 1
    if (Test-AdbDevice $PreferredIp) {
        return $PreferredIp
    }

    Write-Host "Trying mDNS discovery..." -ForegroundColor Yellow
    $services = & $adb mdns services
    $watchService = $services | Select-String "RFAW81T9GVJ.*_adb-tls-connect" | Select-Object -First 1
    if ($watchService) {
        $candidate = ($watchService.ToString() -split "\s+")[0] + "._adb-tls-connect._tcp"
        if (Test-AdbDevice $candidate) {
            return $candidate
        }
    }

    throw "Watch is not connected over ADB. Toggle wireless debugging on the watch, then run this script again."
}

Assert-File $adb "ADB"
Assert-File $gradle "Gradle wrapper"

Push-Location $root
try {
    if (-not $SkipPull) {
        Write-Host "Updating local main from GitHub..." -ForegroundColor Cyan
        Invoke-Git "fetch" "origin" "--prune"
        $status = & git status --porcelain
        if ($status) {
            throw "Working tree has uncommitted changes. Commit, stash, or rerun with -SkipPull."
        }
        Invoke-Git "pull" "--ff-only"
    }

    $head = (& git rev-parse --short HEAD).Trim()
    $subject = (& git log -1 --pretty=%s).Trim()
    Write-Host "Using commit $head $subject" -ForegroundColor Cyan
} finally {
    Pop-Location
}

if (-not $SkipBuild) {
    Write-Host "Building watch APK..." -ForegroundColor Cyan
    $buildStartedAt = Get-Date
    if (Test-Path -LiteralPath $apk) {
        Remove-Item -LiteralPath $apk -Force
    }
    Push-Location $root
    try {
        & $gradle ":app:assembleDebug"
        if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    } finally {
        Pop-Location
    }
} else {
    $buildStartedAt = $null
}

Assert-File $apk "Watch APK"
$apkInfo = Get-Item -LiteralPath $apk
if ($buildStartedAt -and $apkInfo.LastWriteTime -lt $buildStartedAt) {
    throw "APK was not rebuilt during this run. Refusing to install stale APK: $apk"
}
Write-Host "APK: $($apkInfo.FullName)" -ForegroundColor Cyan
Write-Host "APK timestamp: $($apkInfo.LastWriteTime)" -ForegroundColor Cyan

$target = Resolve-WatchSerial -PreferredSerial $WatchSerial -PreferredIp $WatchIp
Write-Host "Installing on $target..." -ForegroundColor Cyan

& $adb -s $target shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $target shell svc power stayon true | Out-Null
& $adb -s $target push $apk $remoteApk
if ($LASTEXITCODE -ne 0) { throw "APK push failed" }

& $adb -s $target shell pm install -r -d $remoteApk
if ($LASTEXITCODE -ne 0) { throw "APK install failed" }

Write-Host "Installed package:" -ForegroundColor Green
& $adb -s $target shell dumpsys package com.fredapp.wbooks | Select-String "versionCode|versionName|lastUpdateTime"
