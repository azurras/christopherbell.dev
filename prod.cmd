@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "PWSH_EXE=pwsh.exe"
where pwsh.exe >nul 2>&1
if not errorlevel 1 goto run
set "PWSH_EXE=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not exist "%PWSH_EXE%" (
    echo PowerShell 7 is required. Install it with: winget install --id Microsoft.PowerShell --exact 1>&2
    exit /b 1
)
:run
"%PWSH_EXE%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%ops\production\windows\prod.ps1" %*
exit /b %ERRORLEVEL%
