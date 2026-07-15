$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) {
    & .\build-debug.ps1
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& adb install -r $apk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host 'APK 已安装。接下来在 LSPosed 中启用模块并勾选 QQ。'
