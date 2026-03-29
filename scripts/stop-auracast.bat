@echo off
title AuraCast — Stop
echo Stopping AuraCast services...
taskkill /FI "WindowTitle eq AuraCast Server*" /F >nul 2>&1
taskkill /FI "WindowTitle eq AuraCast ngrok*"  /F >nul 2>&1
taskkill /F /IM ngrok.exe >nul 2>&1
echo Done.
timeout /t 2 /nobreak >nul
