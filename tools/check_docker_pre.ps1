# 检查 Docker Desktop 安装前置条件：管理员权限、Hyper-V/虚拟机平台状态、CPU 虚拟化
$ErrorActionPreference = 'SilentlyContinue'
$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)
"IsAdmin: " + $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
"HypervisorPresent: " + (Get-CimInstance Win32_ComputerSystem).HypervisorPresent
"NestedVirtualization: " + (Get-CimInstance Win32_ComputerSystem).VirtualizationFirmwareEnabled
foreach ($f in @('Microsoft-Windows-Subsystem-Linux','VirtualMachinePlatform','Microsoft-Hyper-V-All','Containers')) {
    $s = Get-WindowsOptionalFeature -Online -FeatureName $f
    "$f : " + $(if ($s) { $s.State } else { 'NotFound' })
}
"WSL version:"
wsl --version
"CPU: " + (Get-CimInstance Win32_Processor).Name
