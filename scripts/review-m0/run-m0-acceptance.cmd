@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-m0-acceptance.ps1" %*
exit /b %ERRORLEVEL%
