@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-m03-gate.ps1" %*
exit /b %ERRORLEVEL%
