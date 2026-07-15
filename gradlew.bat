@echo off
setlocal EnableExtensions
set "GRADLE_VERSION=8.9"
set "ROOT=%~dp0"
set "BOOT=%ROOT%.gradle-bootstrap"
set "DIST=%BOOT%\gradle-%GRADLE_VERSION%"
set "ZIP=%BOOT%\gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%DIST%\bin\gradle.bat" (
  echo [bootstrap] Downloading Gradle %GRADLE_VERSION%...
  if not exist "%BOOT%" mkdir "%BOOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $url='https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -UseBasicParsing $url -OutFile '%ZIP%'; Expand-Archive -Force '%ZIP%' '%BOOT%'; Remove-Item -Force '%ZIP%'"
  if errorlevel 1 exit /b 1
)

call "%DIST%\bin\gradle.bat" %*
exit /b %errorlevel%
