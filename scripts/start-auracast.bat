@echo off
setlocal enabledelayedexpansion
title AuraCast Launcher
color 0A

echo.
echo  =============================================
echo    AuraCast — Starting all services...
echo  =============================================
echo.

:: Root of the project (one level up from this scripts\ folder)
set "ROOT=%~dp0.."
set "SERVER_DIR=%ROOT%\server"

:: ── Load .env if it exists (preferred over hardcoding secrets here) ──────────
if exist "%SERVER_DIR%\.env" (
    echo  [Config] Loading %SERVER_DIR%\.env
    for /f "usebackq tokens=1,* delims==" %%A in ("%SERVER_DIR%\.env") do (
        set "line=%%A"
        if not "!line:~0,1!"=="#" if not "%%A"=="" (
            set "%%A=%%B"
        )
    )
) else (
    echo  [Config] No .env found.
    echo           Copy server\.env.example to server\.env and fill in secrets.
    echo.
)

:: ── Check Node.js ─────────────────────────────────────────────────────────────
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo  [ERROR] Node.js not found. Install v18+ from https://nodejs.org
    pause & exit /b 1
)
for /f "tokens=*" %%v in ('node -e "process.stdout.write(process.version)"') do set NODE_VER=%%v
echo  [OK]    Node.js %NODE_VER% found.

:: ── Install dependencies if missing ──────────────────────────────────────────
if not exist "%SERVER_DIR%\node_modules\ws" (
    echo  [Setup] Installing server dependencies...
    pushd "%SERVER_DIR%"
    call npm install
    popd
)

:: ── Start Node server in a new window ────────────────────────────────────────
echo  [1/3] Starting AuraCast relay server on port 7000...
start "AuraCast Server" cmd /k "cd /d "%SERVER_DIR%" && npm run start:local"
timeout /t 2 /nobreak >nul

:: ── Start ngrok tunnel ───────────────────────────────────────────────────────
echo  [2/3] Starting ngrok tunnel...
echo.
echo  -----------------------------------------------
echo   Static ngrok domain:
echo     wss://nonmanifestly-smudgeless-lamonica.ngrok-free.dev
echo  -----------------------------------------------
echo.

where ngrok >nul 2>&1
if %errorlevel% equ 0 (
    start "AuraCast ngrok" cmd /k "ngrok http --domain=nonmanifestly-smudgeless-lamonica.ngrok-free.dev 7000"
) else if exist "%ROOT%\ngrok.exe" (
    start "AuraCast ngrok" cmd /k "%ROOT%\ngrok.exe http --domain=nonmanifestly-smudgeless-lamonica.ngrok-free.dev 7000"
) else (
    echo  [WARN] ngrok.exe not found in PATH or project root.
    echo         Download from https://ngrok.com/download and place
    echo         ngrok.exe in the AuraCast\ root folder, then re-run.
    echo.
)

:: ── Open browser ─────────────────────────────────────────────────────────────
echo  [3/3] Opening dashboard in browser...
timeout /t 3 /nobreak >nul
start "" "http://localhost:7000"

echo.
echo  All services started.
echo  To stop: run stop-auracast.bat or close the server/ngrok windows.
echo.
endlocal
pause
