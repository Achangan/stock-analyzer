﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿# stock-analyzer 本地一键启动脚本（请在 Trae 之外的 PowerShell 中运行）
# 用法：
#   启动： powershell -ExecutionPolicy Bypass -File D:\stock-analyzer\start-all.ps1
#   停止： powershell -ExecutionPolicy Bypass -File D:\stock-analyzer\start-all.ps1 -Stop
#
# 说明：数据库类进程需要 fsync / 文件锁等底层文件系统操作，在 IDE 沙箱内会被拦截
# （表现为 mysqld "Can't sync file binlog.index"、Nacos Derby "read-only database"），
# 因此必须在普通 PowerShell 终端中执行。

param(
    [switch]$Stop
)

$ErrorActionPreference = 'Continue'

$Root        = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jdk         = 'C:\Users\22692\tools\jdk8u504-b01'
$JavaExe     = Join-Path $Jdk 'bin\java.exe'
$MysqlExe    = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe'
$MysqlData   = 'D:\mysql-data'
$NacosHome   = 'D:\nacos\nacos'
$LogDir      = Join-Path $Root 'logs'
$AppDir      = $Root
$FrontendDir = Join-Path $Root 'frontend'

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

$Services = @(
    @{ Name = 'quote-service';     Jar = 'quote-service\target\quote-service-0.1.0-SNAPSHOT.jar' },
    @{ Name = 'analysis-service';  Jar = 'analysis-service\target\analysis-service-0.1.0-SNAPSHOT.jar' },
    @{ Name = 'report-service';    Jar = 'report-service\target\report-service-0.1.0-SNAPSHOT.jar' },
    @{ Name = 'portfolio-service'; Jar = 'portfolio-service\target\portfolio-service-0.1.0-SNAPSHOT.jar' },
    @{ Name = 'gateway-service';   Jar = 'gateway-service\target\gateway-service-0.1.0-SNAPSHOT.jar' }
)

function Test-Port([int]$Port) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $Port)
        $c.Close()
        return $true
    } catch {
        return $false
    }
}

function Wait-Port([int]$Port, [int]$TimeoutSec, [string]$Label) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) {
            Write-Host "  [OK] $Label :$Port 已就绪" -ForegroundColor Green
            return $true
        }
        Start-Sleep -Seconds 2
    }
    Write-Host "  [FAIL] $Label :$Port 在 $TimeoutSec 秒内未就绪" -ForegroundColor Red
    return $false
}

function Stop-All {
    Write-Host "停止 stock-analyzer 相关进程..." -ForegroundColor Yellow

    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
        if ($_.CommandLine -and ($_.CommandLine -like '*stock-analyzer*service*.jar*' -or $_.CommandLine -like '*nacos-server.jar*')) {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            Write-Host "  已停止 java PID=$($_.ProcessId)"
        }
    }

    Get-CimInstance Win32_Process -Filter "Name='node.exe'" | ForEach-Object {
        if ($_.CommandLine -and $_.CommandLine -like '*vite*') {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            Write-Host "  已停止 vite PID=$($_.ProcessId)"
        }
    }

    Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" | ForEach-Object {
        if ($_.ExecutablePath -and $_.ExecutablePath -eq $MysqlExe) {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            Write-Host "  已停止 mysqld PID=$($_.ProcessId)"
        }
    }

    Start-Sleep -Seconds 2
    Write-Host "已停止。" -ForegroundColor Green
}

if ($Stop) {
    Stop-All
    exit 0
}

Write-Host "==== stock-analyzer 一键启动 ====" -ForegroundColor Cyan

# 0. 清理旧进程，避免端口与数据目录锁冲突
Stop-All

# 1. 清理崩溃残留的锁文件
Remove-Item (Join-Path $MysqlData '*.pid') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $MysqlData 'mysqld_tmp_file_case_insensitive_test.lower-test') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $NacosHome 'data\derby-data\db.lck') -Force -ErrorAction SilentlyContinue
Write-Host "已清理 MySQL pid / Nacos Derby 锁文件" -ForegroundColor Gray

# 2. MySQL
if (Test-Port 3306) {
    Write-Host "[SKIP] MySQL 3306 已在监听" -ForegroundColor Gray
} else {
    Write-Host "启动 MySQL..." -ForegroundColor Yellow
    Start-Process -FilePath $MysqlExe `
        -ArgumentList "--basedir=`"C:\Program Files\MySQL\MySQL Server 8.4`"", "--datadir=$MysqlData", '--port=3306', '--console' `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir 'mysql.out.log') `
        -RedirectStandardError  (Join-Path $LogDir 'mysql.err.log')
    if (-not (Wait-Port 3306 60 'MySQL')) { Write-Host "请查看 logs\mysql.err.log" -ForegroundColor Red; exit 1 }
}

# 3. Nacos
if (Test-Port 8848) {
    Write-Host "[SKIP] Nacos 8848 已在监听" -ForegroundColor Gray
} else {
    Write-Host "启动 Nacos（standalone）..." -ForegroundColor Yellow
    $env:JAVA_HOME = $Jdk
    Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', "`"$NacosHome\bin\startup.cmd`" -m standalone" `
        -WorkingDirectory $NacosHome -WindowStyle Hidden
    if (-not (Wait-Port 8848 90 'Nacos')) { Write-Host "请查看 $NacosHome\logs\start.out" -ForegroundColor Red; exit 1 }
}

# 4. 五个后端服务（先起业务服务，最后起网关）
Write-Host "启动后端微服务..." -ForegroundColor Yellow
foreach ($s in $Services) {
    $jar = Join-Path $AppDir $s.Jar
    if (-not (Test-Path $jar)) {
        Write-Host "  [MISS] 未找到 $($s.Jar)，请先执行 mvn -DskipTests package" -ForegroundColor Red
        continue
    }
    Start-Process -FilePath $JavaExe `
        -ArgumentList '-Xms128m', '-Xmx384m', '-Dfile.encoding=UTF-8', '-jar', $jar `
        -WorkingDirectory $AppDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir "$($s.Name).log") `
        -RedirectStandardError  (Join-Path $LogDir "$($s.Name).err.log")
    Write-Host "  已启动 $($s.Name)"
    Start-Sleep -Seconds 3
}

foreach ($p in @(8081, 8082, 8083, 8084, 8080)) {
    [void](Wait-Port $p 90 "service")
}

# 5. 前端开发服务器
if (Test-Port 5274) {
    Write-Host "[SKIP] 前端 5274 已在监听" -ForegroundColor Gray
} else {
    Write-Host "启动前端 Vite..." -ForegroundColor Yellow
    Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', 'npm run dev' `
        -WorkingDirectory $FrontendDir -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir 'frontend.log') `
        -RedirectStandardError  (Join-Path $LogDir 'frontend.err.log')
    [void](Wait-Port 5274 90 'frontend')
}

Write-Host ""
Write-Host "==== 启动结果 ====" -ForegroundColor Cyan
@(
    @{ P = 3306; L = 'MySQL' },
    @{ P = 8848; L = 'Nacos' },
    @{ P = 8080; L = 'gateway' },
    @{ P = 8081; L = 'quote' },
    @{ P = 8082; L = 'analysis' },
    @{ P = 8083; L = 'report' },
    @{ P = 8084; L = 'portfolio' },
    @{ P = 5274; L = 'frontend' }
) | ForEach-Object {
    $ok = Test-Port $_.P
    $text = if ($ok) { 'UP' } else { 'DOWN' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("  {0,-10} :{1,-5} {2}" -f $_.L, $_.P, $text) -ForegroundColor $color
}

Write-Host ""
Write-Host "前端地址: http://localhost:5274" -ForegroundColor Cyan
Write-Host "网关接口: http://localhost:8080/api/quote/index/list" -ForegroundColor Cyan
Write-Host "日志目录: $LogDir" -ForegroundColor Gray
Write-Host "停止服务: start-all.ps1 -Stop" -ForegroundColor Gray
