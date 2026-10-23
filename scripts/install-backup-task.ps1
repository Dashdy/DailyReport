# 每日战报系统：在正式服务器 10.10.99.5 上安装“每天自动备份”（数据库 + 照片）。
# 在开发机上用【管理员身份】PowerShell 运行，跟 install-on-server.ps1 一样通过 WinRM 远程操作服务器。
# 可以重复运行（会覆盖旧的备份脚本和任务计划，不会删已有的备份文件）。
#
# 这个脚本做的事：
#   1/4 把 scripts\server-local\backup-dailyreport.ps1 复制到服务器 F:\DailyReport\Tools\
#   2/4 确认服务器上 sqlcmd.exe 的位置（任务计划里 PATH 不可靠，要写完整路径）
#   3/4 注册任务计划 DailyReport-Backup：每天 2:30 用 SYSTEM 账号运行（Dashboard 的备份在 2:00）
#   4/4 马上试跑一次，显示结果和生成的备份文件

$serverIp = "10.10.99.5"
$repoRoot = "D:\WellTek\Projects\DailyReport"
$remoteTools = "F:\DailyReport\Tools"
$taskName = "DailyReport-Backup"
$runAt = "2:30am"

$ErrorActionPreference = "Stop"

Write-Host "连接服务器 $serverIp（会弹出密码框，填服务器本机管理员账号密码）..." -ForegroundColor Cyan
Start-Service WinRM
$trusted = (Get-Item WSMan:\localhost\Client\TrustedHosts).Value
if ($trusted -notlike "*$serverIp*") {
    Set-Item WSMan:\localhost\Client\TrustedHosts -Value $serverIp -Force
}
$cred = Get-Credential -Message "输入服务器 $serverIp 的本地管理员账号密码"
$session = New-PSSession -ComputerName $serverIp -Credential $cred

Write-Host "1/4 复制备份脚本到服务器 $remoteTools ..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $remoteTools -ScriptBlock {
    param($dir) New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
Copy-Item -ToSession $session -Path "$repoRoot\scripts\server-local\backup-dailyreport.ps1" -Destination "$remoteTools\backup-dailyreport.ps1" -Force

Write-Host "2/4 确认服务器上 sqlcmd.exe 的位置..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList "$remoteTools\backup-dailyreport.ps1" -ScriptBlock {
    param($script)
    $content = Get-Content $script -Raw -Encoding UTF8
    $configured = [regex]::Match($content, '\$sqlcmdPath = "([^"]+)"').Groups[1].Value
    if (Test-Path $configured) {
        Write-Host "    使用 $configured"
        return
    }
    # 脚本里写的路径不存在，就在常见安装目录里找一个
    $found = Get-ChildItem "C:\Program Files\Microsoft SQL Server", "C:\Program Files (x86)\Microsoft SQL Server" `
        -Recurse -Filter "SQLCMD.EXE" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $found) { throw "服务器上找不到 SQLCMD.EXE，请手动确认路径后改 $script 里的 `$sqlcmdPath" }
    $content = $content.Replace("`$sqlcmdPath = `"$configured`"", "`$sqlcmdPath = `"$($found.FullName)`"")
    # 保存成 UTF-8 带 BOM（Windows PowerShell 5.1 读中文脚本需要 BOM）
    [System.IO.File]::WriteAllText($script, $content, (New-Object System.Text.UTF8Encoding($true)))
    Write-Host "    脚本里写的路径不存在，已改成 $($found.FullName)"
}

Write-Host "3/4 注册任务计划 $taskName（每天 $runAt，SYSTEM 账号）..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $taskName, "$remoteTools\backup-dailyreport.ps1", $runAt -ScriptBlock {
    param($name, $script, $at)
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $name -Confirm:$false
    }
    $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
    $trigger = New-ScheduledTaskTrigger -Daily -At $at
    $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    # 错过了（比如那时候服务器关机）开机后补跑一次；最多跑 1 小时
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 1)
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
        -Description "每日战报系统：备份 DailyReport 数据库和上传的照片" | Out-Null
    Write-Host "    已注册"
}

Write-Host "4/4 马上试跑一次..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $taskName -ScriptBlock {
    param($name)
    Start-ScheduledTask -TaskName $name
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        if ((Get-ScheduledTask -TaskName $name).State -ne "Running") { break }
    }
    $info = Get-ScheduledTaskInfo -TaskName $name
    Write-Host ""
    Write-Host "---- F:\DailyReport\Logs\backup.log 最后几行 ----"
    Get-Content "F:\DailyReport\Logs\backup.log" -Tail 8 -Encoding UTF8 -ErrorAction SilentlyContinue
    Write-Host ""
    Write-Host "---- F:\DailyReport\Storage\SQLBackup ----"
    Get-ChildItem "F:\DailyReport\Storage\SQLBackup" -Filter "DailyReport_*.bak" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 3 Name, @{n = "MB"; e = { [math]::Round($_.Length / 1MB, 2) } }, LastWriteTime |
        Format-Table -AutoSize | Out-String | Write-Host
    if ($info.LastTaskResult -eq 0) {
        Write-Host "试跑成功。以后每天 $((Get-ScheduledTask -TaskName $name).Triggers[0].StartBoundary.Substring(11,5)) 自动备份。" -ForegroundColor Green
    } else {
        Write-Host "试跑失败（任务返回码 $($info.LastTaskResult)），把上面的日志发给开发看。" -ForegroundColor Red
    }
}

Remove-PSSession $session
