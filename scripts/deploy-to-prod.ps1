# 每日战报系统（DailyReport）：更新正式服务器 10.10.99.5 上的代码。
# 第一次安装请先跑 install-on-server.ps1。
#
# 只换代码（后端 jar + 前端 dist），不碰服务器上的 application-prod.properties（正式密码），
# 也不碰数据库（表结构有变化时后端启动会自动补，不需要手动跑 SQL）。
#
# 前提：管理员身份 PowerShell；开发机上先停掉本地调试用的 DailyReport 后端。

$serverIp = "10.10.99.5"
$repoRoot = "D:\WellTek\Projects\DailyReport"
$javaHome = "D:\WellTek\SoftWare\JDK\Install\jdk-17.0.19"
$serviceName = "DailyReport-Backend"
$remoteRoot = "F:\DailyReport"
$webPort = 8085

$ErrorActionPreference = "Stop"

Write-Host "0/5 连接服务器 $serverIp（会弹出密码框）..." -ForegroundColor Cyan
Start-Service WinRM
$trusted = (Get-Item WSMan:\localhost\Client\TrustedHosts).Value
if ($trusted -notlike "*$serverIp*") {
    Set-Item WSMan:\localhost\Client\TrustedHosts -Value $serverIp -Force
}
$cred = Get-Credential -Message "输入服务器 $serverIp 的本地管理员账号密码"
$session = New-PSSession -ComputerName $serverIp -Credential $cred

$svc = Invoke-Command -Session $session -ArgumentList $serviceName -ScriptBlock {
    param($name) Get-Service -Name $name -ErrorAction SilentlyContinue
}
if (-not $svc) {
    Remove-PSSession $session
    throw "服务器上还没有 $serviceName 服务，请先运行 install-on-server.ps1"
}

Write-Host "1/5 本地打包后端..." -ForegroundColor Cyan
$env:JAVA_HOME = $javaHome
Push-Location "$repoRoot\Backend\backend"
.\mvnw.cmd -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "后端打包失败，看上面的报错" }
Pop-Location
$jar = Get-ChildItem "$repoRoot\Backend\backend\target\backend-*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) { throw "没找到打包出来的 jar" }

Write-Host "2/5 本地构建前端..." -ForegroundColor Cyan
Push-Location "$repoRoot\Frontend\frontend"
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "前端构建失败，看上面的报错" }
Pop-Location

Write-Host "3/5 停止服务器上的 $serviceName..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $serviceName -ScriptBlock { param($name) Stop-Service -Name $name -Force }

Write-Host "4/5 推送新 jar 和前端 dist..." -ForegroundColor Cyan
Copy-Item -ToSession $session -Path $jar.FullName -Destination "$remoteRoot\App\DailyReport.jar" -Force
Invoke-Command -Session $session -ArgumentList "$remoteRoot\wwwroot\dist" -ScriptBlock {
    param($dist) Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
}
Copy-Item -ToSession $session -Recurse -Path "$repoRoot\Frontend\frontend\dist" -Destination "$remoteRoot\wwwroot\dist" -Force

Write-Host "5/5 启动服务并检查..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $serviceName, $webPort -ScriptBlock {
    param($name, $port)
    Start-Service -Name $name
    $ok = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        try {
            Invoke-WebRequest "http://127.0.0.1:$port/api/me" -UseBasicParsing | Out-Null
            $ok = $true; break
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) { $ok = $true; break }
        }
    }
    if ($ok) {
        Write-Host "    后端已启动，http://127.0.0.1:$port 能访问" -ForegroundColor Green
    } else {
        Write-Host "    60 秒内没等到后端响应，看日志：F:\DailyReport\Logs\backend.log" -ForegroundColor Red
        Get-Content "F:\DailyReport\Logs\backend.log" -Tail 30 -ErrorAction SilentlyContinue
    }
}

Remove-PSSession $session
Write-Host ""
Write-Host "完成：浏览器打开 http://${serverIp}:$webPort 验证。" -ForegroundColor Green
