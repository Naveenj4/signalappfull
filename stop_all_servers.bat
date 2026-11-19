@echo off
title PIT Messenger Server Stack Stopper
echo ============================================================
echo   PIT Messenger - Stopping Server Stack
echo ============================================================

echo Stopping Signal Server (port 8080)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Stopping Custom Backend (port 8088)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8088 " ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Done. Servers have been stopped.
pause
