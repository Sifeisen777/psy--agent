@echo off
cd /d "%~dp0"
title psy-agent frontend

if not exist "node_modules" (
    echo [1/2] Installing dependencies via npmmirror, about 40s...
    call npm install --registry=https://registry.npmmirror.com
    if errorlevel 1 (
        echo.
        echo [FAILED] npm install failed. Check network and retry.
        pause
        exit /b 1
    )
    echo.
)

echo ============================================
echo   psy-agent frontend
echo ============================================
echo   - backend port auto-detected (8080/1808x)
echo   - browser opens automatically
echo   - to stop: run the "stop" cmd in this folder
echo ============================================
echo.

call npm run dev

echo.
echo Dev server stopped.
pause
