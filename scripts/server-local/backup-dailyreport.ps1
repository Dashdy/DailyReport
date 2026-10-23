# 【服务器本机脚本，不是从开发机远程跑的】每天定时备份每日战报系统：
#   1. 数据库 DailyReport → F:\DailyReport\Storage\SQLBackup\DailyReport_yyyyMMdd_HHmmss.bak（保留 14 天）
#   2. 上传的照片（班组照片、签名照片）→ F:\DailyReport\Storage\UploadsBackup\（只增不删的镜像）
# 运行记录追加到 F:\DailyReport\Logs\backup.log。
#
# 写法照搬 Dashboard 的 scripts\server-local\backup-welltek-db.ps1（服务器上已经在跑），
# 两个备份互不影响。
#
# 部署步骤（在服务器本机【管理员身份】PowerShell 里）：
#   1. 把这个文件复制到 F:\DailyReport\Tools\backup-dailyreport.ps1
#   2. 先手动跑一次，看有没有生成 .bak：
#        powershell -NoProfile -ExecutionPolicy Bypass -File "F:\DailyReport\Tools\backup-dailyreport.ps1"
#   3. 注册每天凌晨 2:30 自动运行（Dashboard 的备份在 2:00，错开半小时）：
#        $action = New-ScheduledTaskAction -Execute "powershell.exe" `
#            -Argument '-NoProfile -ExecutionPolicy Bypass -File "F:\DailyReport\Tools\backup-dailyreport.ps1"'
#        $trigger = New-ScheduledTaskTrigger -Daily -At 2:30am
#        $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
#        Register-ScheduledTask -TaskName "DailyReport-Backup" -Action $action -Trigger $trigger -Principal $principal
#   4. 想立刻用任务计划跑一次验证：Start-ScheduledTask -TaskName "DailyReport-Backup"，
#      然后看 F:\DailyReport\Logs\backup.log
#
# 用 SYSTEM 账号跑，通过 Windows 信任连接（sqlcmd -E）备份，不需要写数据库密码。
# 恢复方法见 scripts\说明.txt。
#
# 注意：备份和数据库在同一台服务器上，防得住误删数据、系统盘重装，防不住服务器整机损坏；
# 需要的话再把 SQLBackup 和 UploadsBackup 两个文件夹定期拷到别的机器上。

$ErrorActionPreference = "Stop"

$sqlInstance = "localhost\SQLS2022"
$databaseName = "DailyReport"
$backupDir = "F:\DailyReport\Storage\SQLBackup"
$uploadsBackupDir = "F:\DailyReport\Storage\UploadsBackup"
$defaultUploadDir = "F:\DailyReport\Storage\uploads"   # 跟 application-prod.properties 里的 app.upload-dir 一致
$retentionDays = 14
$logFile = "F:\DailyReport\Logs\backup.log"
# 任务计划（SYSTEM 账号）里 PATH 可能找不到 sqlcmd，跟 Dashboard 一样写完整路径；服务器上装的路径不同就改这一行
$sqlcmdPath = "C:\Program Files\Microsoft SQL Server\Client SDK\ODBC\170\Tools\Binn\SQLCMD.EXE"

foreach ($d in $backupDir, $uploadsBackupDir, (Split-Path $logFile)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
}

function Log($msg) {
    $line = "{0}  {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

try {
    # ---- 0. 让 SQL Server 服务账号能写备份文件夹 ----
    # BACKUP DATABASE 是 SQL Server 服务进程自己去写 .bak 文件的（不是运行这个脚本的账号），
    # 它的账号（如 NT Service\MSSQL$SQLS2022）没有这个文件夹的写权限时会报“操作系统错误 5，拒绝访问”。
    $instanceName = ($sqlInstance -split "\\")[-1]
    $svcName = if ($sqlInstance -like "*\*") { "MSSQL`$$instanceName" } else { "MSSQLSERVER" }
    $svc = Get-CimInstance Win32_Service -Filter "Name = '$svcName'" -ErrorAction SilentlyContinue
    if ($svc -and $svc.StartName) {
        icacls $backupDir /grant "$($svc.StartName):(OI)(CI)M" /Q | Out-Null
    }

    # ---- 1. 数据库 ----
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $backupPath = Join-Path $backupDir "DailyReport_$timestamp.bak"
    Log "开始备份数据库 $databaseName → $backupPath"
    # 不加 WITH COMPRESSION：Express 版不支持，加了会直接失败
    & $sqlcmdPath -S $sqlInstance -d master -E -C -b -Q "BACKUP DATABASE [$databaseName] TO DISK = N'$backupPath' WITH INIT, CHECKSUM, STATS = 25"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backupPath)) {
        throw "数据库备份失败（sqlcmd 退出码 $LASTEXITCODE），看上面 sqlcmd 的输出"
    }
    $sizeMb = [math]::Round((Get-Item $backupPath).Length / 1MB, 1)
    Log "数据库备份完成（$sizeMb MB）"

    $cutoff = (Get-Date).AddDays(-$retentionDays)
    Get-ChildItem -Path $backupDir -Filter "DailyReport_*.bak" |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        ForEach-Object {
            Remove-Item $_.FullName -Force
            Log "删除过期备份：$($_.Name)"
        }

    # ---- 2. 照片 ----
    # 系统管理员可能在“系统设置”里改过存储路径，优先读数据库里的设置
    $uploadDir = $defaultUploadDir
    $fromDb = & $sqlcmdPath -S $sqlInstance -d $databaseName -E -C -h -1 -W -Q "SET NOCOUNT ON; SELECT SETTING_VALUE FROM dbo.SYS_SETTING WHERE SETTING_KEY = 'UPLOAD_DIR'" 2>$null
    $fromDb = ($fromDb | Where-Object { $_ -and $_.Trim() }) | Select-Object -First 1
    if ($fromDb -and (Test-Path $fromDb.Trim())) { $uploadDir = $fromDb.Trim() }

    if (Test-Path $uploadDir) {
        Log "开始备份照片 $uploadDir → $uploadsBackupDir"
        # /E 含子文件夹；不加 /MIR，源文件被删了备份里也留着（照片文件名都是随机的，不会互相覆盖）
        robocopy $uploadDir $uploadsBackupDir /E /XO /R:2 /W:5 /NP /NFL /NDL /NJH | Out-Null
        if ($LASTEXITCODE -ge 8) { throw "照片备份失败（robocopy 退出码 $LASTEXITCODE）" }
        $count = (Get-ChildItem $uploadsBackupDir -Recurse -File).Count
        Log "照片备份完成（备份里共 $count 张）"
    } else {
        Log "照片目录 $uploadDir 还不存在（还没人上传过照片），跳过"
    }

    Log "全部完成"
} catch {
    Log "备份失败：$($_.Exception.Message)"
    exit 1
}
