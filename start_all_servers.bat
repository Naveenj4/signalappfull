@echo off
setlocal enabledelayedexpansion
title PIT Messenger Server Stack Launcher
echo ============================================================
echo   PIT Messenger - Starting Complete Server Stack
echo ============================================================
cd /d "%~dp0"

:: 1. Check Redis
echo [1/4] Checking Redis on port 6379...
netstat -ano | findstr ":6379 " >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo       Redis is active on port 6379.
) else (
    echo       Starting Redis server...
    if exist "D:\redis\redis-server.exe" (
        start "PIT Redis Server" /min "D:\redis\redis-server.exe" "D:\redis\redis-signal.conf"
    ) else if exist "%~dp0redis\redis-server.exe" (
        start "PIT Redis Server" /min "%~dp0redis\redis-server.exe" "%~dp0redis\redis-signal.conf"
    ) else (
        echo       [NOTE] Ensure Redis service is running locally on port 6379.
    )
)

:: 2. Check MySQL
echo [2/4] Checking MySQL on port 3306...
netstat -ano | findstr ":3306 " >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo       MySQL is reachable on port 3306.
) else (
    echo       [WARNING] Port 3306 is not listening! Please start your MySQL service.
)

:: 3. Start Custom Backend (Node.js) on port 8088
echo [3/4] Starting Custom Backend microservice on port 8088...
start "PIT Custom Backend (Port 8088)" cmd /k "cd /d %~dp0custom-backend && node server.js"

:: 4. Start Signal Server (Java Dropwizard) on port 8080/8081
echo [4/4] Starting Signal Server (Dropwizard) on port 8080/8081...
start "PIT Signal Server (Port 8080/8081)" powershell -NoExit -ExecutionPolicy Bypass -File "%~dp0signal-server\scripts\start-pit-server.ps1"

echo ============================================================
echo   Server stack launched!
echo   - Custom Backend:  http://127.0.0.1:8088
echo   - Signal Server:   http://127.0.0.1:8080
echo   - Admin / Health:  http://127.0.0.1:8081/healthcheck
echo ============================================================
pause
