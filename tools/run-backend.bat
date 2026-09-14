@echo off
REM =====================================================================
REM  个人博客系统 · 开发后端启动器（本文件以 GBK 编码保存，勿另存为 UTF-8）
REM  由 start-dev.bat 拉起的独立窗口使用，也可单独双击运行。
REM  先加载 backend\.env（DB 口令/AI Key；gitignore 文件，仓库零明文），
REM  再以 spring-boot:run 起 :8080。
REM =====================================================================
setlocal EnableExtensions

if not exist "D:\毕业设计\backend\.env" goto :noenv
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("D:\毕业设计\backend\.env") do set "%%a=%%b"
:noenv

cd /d D:\毕业设计\backend
E:\develop\apache-maven-3.9.4\bin\mvn.cmd spring-boot:run
echo.
echo （后端进程已退出，常见原因：8080 被占用 / DB 口令不对，回看上方日志）
pause
