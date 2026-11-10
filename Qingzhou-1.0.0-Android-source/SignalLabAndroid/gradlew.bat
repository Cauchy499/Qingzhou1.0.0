@echo off
setlocal
set GRADLE_VERSION=8.9
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_DIR=%GRADLE_USER_HOME%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\signallab
set GRADLE_BIN=%DIST_DIR%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if not exist "%GRADLE_BIN%" (
  if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_DIR%\gradle.zip'; Expand-Archive -Force '%DIST_DIR%\gradle.zip' '%DIST_DIR%'"
)
call "%GRADLE_BIN%" -p "%~dp0" %*
endlocal
