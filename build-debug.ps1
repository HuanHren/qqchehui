$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Warning '未检测到 ANDROID_HOME/ANDROID_SDK_ROOT。用 Android Studio 打开工程后安装 Android SDK 35，或先配置 SDK 环境变量。'
}

& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host "构建完成：$apk"
