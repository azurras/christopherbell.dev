@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%ops\production\windows\prod.ps1" %*
exit /b %ERRORLEVEL%
