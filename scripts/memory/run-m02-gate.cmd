@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-m02-gate.ps1" %*
exit /b %ERRORLEVEL%
