@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-frontend-contract-gate.ps1" %*
exit /b %ERRORLEVEL%
