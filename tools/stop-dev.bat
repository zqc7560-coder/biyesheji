@echo off
REM =====================================================================
REM  个人博客系统 · 开发模式一键停止（本文件以 GBK 编码保存，勿另存为 UTF-8）
REM  只杀命令行含 spring-boot/blog-server 的 java 与含 vite 的 node，
REM  其它 java/node 进程零误伤。Docker 全栈(方式二)请用 docker compose down。
REM =====================================================================
echo 停止后端 (spring-boot / blog-server)...
powershell -NoProfile -Command "$n=0; Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'java' -and $_.CommandLine -match 'spring-boot|blog-server' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $n++ }; if($n){Write-Host ('  已结束 '+$n+' 个进程')}else{Write-Host '  未发现运行中的后端'}"

echo 停止前端 (vite)...
powershell -NoProfile -Command "$n=0; Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'node' -and $_.CommandLine -match 'vite' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $n++ }; if($n){Write-Host ('  已结束 '+$n+' 个进程')}else{Write-Host '  未发现运行中的前端'}"

echo 完成。（MySQL/Redis 系统服务不受影响；两个黑窗口可手动关闭）
ping -n 4 127.0.0.1 >nul
