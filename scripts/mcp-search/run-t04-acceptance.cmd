@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-t04-acceptance.ps1" %*
exit /b %ERRORLEVEL%
