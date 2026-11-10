param(
    [string]$Apk,
    [string]$Adb
)

$ErrorActionPreference = 'Continue'

# 解析 adb：优先显式 -Adb，其次 ANDROID_HOME / ANDROID_SDK_ROOT，最后 PATH
if (-not $Adb) {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += (Join-Path $env:USERPROFILE 'Android\Sdk\platform-tools\adb.exe')
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath $c) { $Adb = $c; break }
    }
    if (-not $Adb) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $Adb = $cmd.Source }
    }
    if (-not $Adb) { throw 'adb not found: pass -Adb <path>, or set ANDROID_HOME / add adb to PATH' }
}

# 未指定 -Apk 时，取同目录最新的带版本产物（文件名由 build.ps1 按 versionName 生成）
if (-not $Apk) {
    $latest = Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'PedestrianWarningOff-Yunduo-v*.apk' -File |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { throw 'no PedestrianWarningOff-Yunduo-v*.apk found: run build.ps1 first, or pass -Apk <path>' }
    $Apk = $latest.FullName
}

if (-not (Test-Path -LiteralPath $Apk)) { throw "APK not found: $Apk" }

Write-Host "[1/4] install -r" -ForegroundColor Cyan
& $Adb install -r $Apk

# 关键一步：install 后应用是 stopped 状态，收不到 BOOT_COMPLETED，必须启动一次解除
Write-Host "[2/4] am start  (解 stopped + 立即启动 GuardService)" -ForegroundColor Cyan
& $Adb shell am start -n com.sgmwyunduo.pwoff/.MainActivity

Start-Sleep -Seconds 2

Write-Host "[3/4] 权限校验 (期望 granted=true)" -ForegroundColor Cyan
& $Adb shell dumpsys package com.sgmwyunduo.pwoff | Select-String "android.car.permission.CAR_INFO"

Write-Host "[4/4] 运行日志" -ForegroundColor Cyan
& $Adb shell logcat -d -s PwOff
