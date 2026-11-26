@echo off
REM 启动脚本：解决 Windows 控制台中文日志乱码
REM   chcp 65001  —— 把控制台代码页切成 UTF-8
REM   -Dfile.encoding=UTF-8 —— 让 JVM 按 UTF-8 编码标准输出
REM 用法：scripts\run.cmd            默认 8080
REM       scripts\run.cmd --server.port=18080

setlocal
chcp 65001 >nul

set "JAR=%~dp0..\psy-bootstrap\target\psy-bootstrap-1.0.0-SNAPSHOT.jar"

if not exist "%JAR%" (
    echo [ERROR] 未找到 jar，请先执行：mvn -DskipTests package
    exit /b 1
)

java -Dfile.encoding=UTF-8 -jar "%JAR%" %*
endlocal
