# 每日战报系统（DailyReport）：第一次安装到正式服务器 10.10.99.5（跟 Dashboard 同一台）。
# 只需要跑一次；以后更新代码用 deploy-to-prod.ps1。
#
# 前提（跟 Dashboard 的 deploy-to-prod.ps1 一样）：
#   1. 必须在【管理员身份】的 PowerShell 里跑（要改 WinRM 的 TrustedHosts）。
#   2. 服务器已经 Enable-PSRemoting，网络配置是 Private（Dashboard 部署时已经弄好）。
#   3. 开发机上先停掉本地调试用的 DailyReport 后端（否则 mvnw clean 删不掉被占用的 jar）。
#
# 这个脚本做的事：
#   1/8 本地打包后端 jar、构建前端 dist
#   2/8 连接服务器，建目录 F:\DailyReport\{App\config, wwwroot, Storage\uploads, Logs}
#   3/8 推送 jar、前端 dist；正式配置模板只在服务器上还没有时才复制（不会覆盖已经改好的密码）
#   4/8 用服务器本机 Windows 信任连接（sqlcmd -E）创建 DailyReport 数据库（已存在就跳过）
#   5/8 用跟 Dashboard（WellTek-Backend）同一个 nssm.exe 和 java.exe 注册 DailyReport-Backend 服务，开机自启
#   6/8 防火墙放行 8085 端口
#   7/8 把 Nginx 配置放进 conf\conf.d\ 并 reload（Dashboard 的 80 端口不受影响）
#   8/8 正式配置里的数据库密码还没改 → 不启动服务，提示去改；已经改好 → 启动服务并检查能不能访问

$serverIp = "10.10.99.5"
$repoRoot = "D:\WellTek\Projects\DailyReport"
$javaHome = "D:\WellTek\SoftWare\JDK\Install\jdk-17.0.19"
$serviceName = "DailyReport-Backend"
$dashboardService = "WellTek-Backend"   # 借用它的 nssm.exe 和 java.exe 路径，服务器上不用另装
$sqlInstance = "localhost\SQLS2022"     # 服务器上 SQL Server 的实例名，跟 Dashboard 用的同一个
$remoteRoot = "F:\DailyReport"
$webPort = 8085

$ErrorActionPreference = "Stop"

Write-Host "1/8 本地打包后端、构建前端..." -ForegroundColor Cyan
$env:JAVA_HOME = $javaHome
Push-Location "$repoRoot\Backend\backend"
.\mvnw.cmd -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "后端打包失败，看上面的报错" }
Pop-Location
$jar = Get-ChildItem "$repoRoot\Backend\backend\target\backend-*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) { throw "没找到打包出来的 jar" }

Push-Location "$repoRoot\Frontend\frontend"
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "前端构建失败，看上面的报错" }
Pop-Location

Write-Host "2/8 连接服务器 $serverIp（会弹出密码框，填服务器本机管理员账号密码）..." -ForegroundColor Cyan
Start-Service WinRM
$trusted = (Get-Item WSMan:\localhost\Client\TrustedHosts).Value
if ($trusted -notlike "*$serverIp*") {
    Set-Item WSMan:\localhost\Client\TrustedHosts -Value $serverIp -Force
}
$cred = Get-Credential -Message "输入服务器 $serverIp 的本地管理员账号密码"
$session = New-PSSession -ComputerName $serverIp -Credential $cred

Invoke-Command -Session $session -ArgumentList $remoteRoot -ScriptBlock {
    param($root)
    foreach ($d in "App\config", "wwwroot", "Storage\uploads", "Storage\sql", "Logs") {
        New-Item -ItemType Directory -Force -Path (Join-Path $root $d) | Out-Null
    }
}

Write-Host "3/8 推送程序和网页..." -ForegroundColor Cyan
$svcExists = Invoke-Command -Session $session -ArgumentList $serviceName -ScriptBlock {
    param($name) [bool](Get-Service -Name $name -ErrorAction SilentlyContinue)
}
if ($svcExists) {
    Invoke-Command -Session $session -ArgumentList $serviceName -ScriptBlock { param($name) Stop-Service -Name $name -Force }
}
Copy-Item -ToSession $session -Path $jar.FullName -Destination "$remoteRoot\App\DailyReport.jar" -Force
Invoke-Command -Session $session -ArgumentList "$remoteRoot\wwwroot\dist" -ScriptBlock {
    param($dist) Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
}
Copy-Item -ToSession $session -Recurse -Path "$repoRoot\Frontend\frontend\dist" -Destination "$remoteRoot\wwwroot\dist" -Force

$hasConfig = Invoke-Command -Session $session -ArgumentList "$remoteRoot\App\config\application-prod.properties" -ScriptBlock {
    param($p) Test-Path $p
}
if ($hasConfig) {
    Write-Host "    服务器上已有正式配置 application-prod.properties，保留不动" -ForegroundColor Gray
} else {
    Copy-Item -ToSession $session -Path "$repoRoot\deploy\application-prod.properties.example" -Destination "$remoteRoot\App\config\application-prod.properties"
    Write-Host "    已复制正式配置模板到服务器（数据库密码需要改）" -ForegroundColor Yellow
}

Write-Host "4/8 创建数据库 DailyReport（已存在就跳过）..." -ForegroundColor Cyan
Copy-Item -ToSession $session -Path "$repoRoot\scripts\sql\2026-10-23-create-dailyreport-database.sql" -Destination "$remoteRoot\Storage\sql\" -Force
Invoke-Command -Session $session -ArgumentList $sqlInstance, "$remoteRoot\Storage\sql\2026-10-23-create-dailyreport-database.sql" -ScriptBlock {
    param($instance, $file)
    sqlcmd -S $instance -E -C -b -f 65001 -i $file
    if ($LASTEXITCODE -ne 0) { throw "创建数据库失败（实例 $instance），检查脚本顶部的 `$sqlInstance 是否正确" }
}

Write-Host "5/8 注册后端服务 $serviceName..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $serviceName, $dashboardService, $remoteRoot -ScriptBlock {
    param($name, $dash, $root)
    $imagePath = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$dash" -ErrorAction SilentlyContinue).ImagePath
    if (-not $imagePath) { throw "服务器上找不到 Dashboard 的服务 $dash，没法借用它的 nssm / java 路径" }
    $nssm = $imagePath.Trim().Trim('"')
    $java = ((& $nssm get $dash Application) -join "") -replace "`0", ""
    $java = $java.Trim()
    if (-not (Test-Path $java)) { throw "从 $dash 读到的 java 路径不存在：$java" }
    Write-Host "    nssm：$nssm"
    Write-Host "    java：$java"

    if (-not (Get-Service -Name $name -ErrorAction SilentlyContinue)) {
        & $nssm install $name $java | Out-Null
    }
    & $nssm set $name Application $java | Out-Null
    & $nssm set $name AppParameters "-Dfile.encoding=UTF-8 -jar `"$root\App\DailyReport.jar`" --spring.profiles.active=prod" | Out-Null
    & $nssm set $name AppDirectory "$root\App" | Out-Null
    & $nssm set $name DisplayName "DailyReport Backend" | Out-Null
    & $nssm set $name Description "每日战报系统后端（端口 8083，通过 Nginx 8085 对外）" | Out-Null
    & $nssm set $name Start SERVICE_AUTO_START | Out-Null
    & $nssm set $name AppStdout "$root\Logs\backend.log" | Out-Null
    & $nssm set $name AppStderr "$root\Logs\backend.log" | Out-Null
    & $nssm set $name AppRotateFiles 1 | Out-Null
    & $nssm set $name AppRotateOnline 1 | Out-Null
    & $nssm set $name AppRotateBytes 10485760 | Out-Null
}

Write-Host "6/8 防火墙放行 $webPort 端口..." -ForegroundColor Cyan
Invoke-Command -Session $session -ArgumentList $webPort -ScriptBlock {
    param($port)
    $ruleName = "DailyReport Web $port"
    if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $port -Action Allow | Out-Null
        Write-Host "    已新增防火墙规则：$ruleName"
    } else {
        Write-Host "    防火墙规则已存在"
    }
}

Write-Host "7/8 配置 Nginx（$webPort 端口）..." -ForegroundColor Cyan
Copy-Item -ToSession $session -Path "$repoRoot\deploy\nginx\dailyreport.conf" -Destination "$remoteRoot\Storage\dailyreport.conf" -Force
$nginxOk = Invoke-Command -Session $session -ArgumentList "$remoteRoot\Storage\dailyreport.conf" -ScriptBlock {
    param($conf)
    $proc = Get-Process nginx -ErrorAction SilentlyContinue | Where-Object { $_.Path } | Select-Object -First 1
    if (-not $proc) { Write-Host "    服务器上没找到正在运行的 nginx 进程" -ForegroundColor Red; return $false }
    $dir = Split-Path $proc.Path
    $main = Get-Content "$dir\conf\nginx.conf" -Raw
    if ($main -notmatch "conf\.d") {
        Write-Host "    $dir\conf\nginx.conf 里没有 include conf.d，需要手动处理" -ForegroundColor Red
        return $false
    }
    New-Item -ItemType Directory -Force -Path "$dir\conf\conf.d" | Out-Null
    Copy-Item $conf "$dir\conf\conf.d\dailyreport.conf" -Force
    Push-Location $dir
    $test = & .\nginx.exe -t 2>&1
    $testOk = $LASTEXITCODE -eq 0
    if ($testOk) { & .\nginx.exe -s reload 2>&1 | Out-Null }
    Pop-Location
    if (-not $testOk) {
        Remove-Item "$dir\conf\conf.d\dailyreport.conf" -Force   # 配置有错就撤掉，别影响 Dashboard
        Write-Host ($test | Out-String) -ForegroundColor Red
        return $false
    }
    Write-Host "    已放入 $dir\conf\conf.d\dailyreport.conf 并 reload"
    return $true
}
if (-not $nginxOk) {
    Write-Host "    Nginx 没有自动配好：请到服务器上把 F:\DailyReport\Storage\dailyreport.conf 放进 Nginx 的 conf\conf.d\，再 nginx -s reload" -ForegroundColor Yellow
}

Write-Host "8/8 启动后端..." -ForegroundColor Cyan
$needEdit = Invoke-Command -Session $session -ArgumentList "$remoteRoot\App\config\application-prod.properties" -ScriptBlock {
    param($p) (Get-Content $p -Raw -Encoding UTF8) -match "请改成"
}
if ($needEdit) {
    Remove-PSSession $session
    Write-Host ""
    Write-Host "还差最后一步：服务器上的数据库密码还没填，服务暂不启动。" -ForegroundColor Yellow
    Write-Host "  1. 在服务器上打开 $remoteRoot\App\config\application-prod.properties"
    Write-Host "     把 spring.datasource.password= 后面改成数据库真实密码（实例名不对的话一起改）"
    Write-Host "  2. 在服务器上执行：Start-Service $serviceName"
    Write-Host "  3. 浏览器打开 http://${serverIp}:$webPort ，用 系统管理员（账号 admin 或 系统管理员）登录"
    Write-Host "     第一次部署是空库，初始账号：系统管理员 / admin、叶丰龙 / 0625，登录后马上改密码"
    exit 0
}

Invoke-Command -Session $session -ArgumentList $serviceName, $webPort -ScriptBlock {
    param($name, $port)
    Start-Service -Name $name
    # 后端启动大约 10~20 秒；/api/me 没登录时返回 401，说明后端和 Nginx 都通了
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
Write-Host "安装完成：浏览器打开 http://${serverIp}:$webPort" -ForegroundColor Green
Write-Host "第一次部署是空库，初始账号：系统管理员 / admin、叶丰龙 / 0625，登录后马上改密码。"
