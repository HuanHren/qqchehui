$ErrorActionPreference = 'Stop'
adb logcat -c
Write-Host '正在显示 QQ 防撤回日志，按 Ctrl+C 停止...'
adb logcat | Select-String -Pattern 'QQAntiRevoke|LSPosed|Xposed'
