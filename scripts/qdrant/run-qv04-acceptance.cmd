@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-qv04-acceptance.ps1" %*
exit /b %ERRORLEVEL%
