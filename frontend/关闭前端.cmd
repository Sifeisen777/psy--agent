@echo off
title stop psy-agent frontend

echo Stopping frontend (port 5173)...
echo.

set FOUND=0

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5173 " ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
    echo   killed PID=%%a
    set FOUND=1
)

if "%FOUND%"=="1" (
    echo.
    echo Frontend stopped, port 5173 released.
) else (
    echo No listener on port 5173.
    echo If a console window is still open, just close it.
)

echo.
pause
