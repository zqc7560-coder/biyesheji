@echo off
REM =====================================================================
REM  个人博客系统 · 开发模式一键启动（本文件以 GBK 编码保存，勿另存为 UTF-8）
REM  依赖：本机 MySQL(3306) / Redis(6379) 服务（随系统自启，脚本会先探测）
REM  动作：新开两个窗口——后端 Spring Boot :8080、前端 Vite dev :5173
REM        （分别经 run-backend.bat / run-frontend.bat，凭据从 backend\.env 读）
REM  停止：同目录 stop-dev.bat
REM =====================================================================
setlocal EnableExtensions

echo [1/3] 探测本机中间件 (MySQL 3306 / Redis 6379)...
powershell -NoProfile -Command "foreach($p in 3306,6379){$s='DOWN'; if(Test-NetConnection 127.0.0.1 -Port $p -InformationLevel Quiet -WarningAction SilentlyContinue){$s='UP'}; Write-Output ($p.ToString()+' '+$s)}" > "%TEMP%\blog_dev_probe.txt"
findstr /C:"DOWN" "%TEMP%\blog_dev_probe.txt" >nul
if not errorlevel 1 (
    echo     中间件未就绪，端口状态：
    type "%TEMP%\blog_dev_probe.txt"
    echo     请先启动缺失的服务（MySQL 服务 / E:\develop\redis-8.10.1\redis-server）后重跑。
    del "%TEMP%\blog_dev_probe.txt" >nul 2>nul
    pause
    exit /b 1
)
type "%TEMP%\blog_dev_probe.txt"
del "%TEMP%\blog_dev_probe.txt" >nul 2>nul

if not exist "D:\毕业设计\backend\.env" (
    echo     提醒：未找到 backend\.env——若本机 MySQL root 有密码，后端将连库失败。
)

echo [2/3] 启动后端（新窗口，首次编译约 1 分钟，看到 Started BlogServerApplication 即就绪）...
start "blog-backend-8080" cmd /k "D:\毕业设计\tools\run-backend.bat"

echo [3/3] 启动前端（新窗口，Vite 秒起）...
start "blog-frontend-5173" cmd /k "D:\毕业设计\tools\run-frontend.bat"

echo.
echo   浏览器访问：   http://localhost:5173
echo   后端健康检查： http://localhost:8080/api/health （mysql/redis 均 UP 即全就绪）
echo   管理员账号：   admin（口令见任务书）
echo.
echo   关闭对应窗口即停止该服务；或运行 stop-dev.bat 一键清理。
pause
endlocal
