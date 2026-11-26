@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   psy-agent 前端启动器
echo ============================================
echo.

if not exist "node_modules" (
    echo [1/2] 首次运行，安装依赖（使用国内镜像，约 40 秒）...
    call npm install --registry=https://registry.npmmirror.com
    if errorlevel 1 (
        echo.
        echo [失败] 依赖安装失败。请检查网络，或手动执行：
        echo     npm install --registry=https://registry.npmmirror.com
        pause
        exit /b 1
    )
    echo.
)

echo [2/2] 启动开发服务器（会自动探测后端端口并打开浏览器）...
echo.
call npm run dev

echo.
echo 开发服务器已退出。
pause
