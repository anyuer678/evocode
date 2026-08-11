# EvoCode 环境自检（页面打不开/接口 500 时先跑这个）
# 用法：.\scripts\check-env.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$issues = @()

function Check($name, $ok, $hint) {
    $mark = if ($ok) { 'OK  ' } else { 'FAIL' }
    Write-Host ("[{0}] {1}" -f $mark, $name) -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
    if (-not $ok) { $script:issues += $hint }
}

Write-Host '==== EvoCode 环境自检 ====' -ForegroundColor Cyan

# 1. Docker 引擎 + 容器
Write-Host '--- 1. 基础设施 ---' -ForegroundColor Yellow
docker ps > $null 2>&1
$dockerOk = ($LASTEXITCODE -eq 0)
Check 'Docker 引擎' $dockerOk '启动 Docker Desktop，等引擎就绪（托盘图标变绿）后重跑本脚本'

if ($dockerOk) {
    $pg = docker ps --filter "name=evocode-postgres" --format "{{.Names}}" 2>$null
    $redis = docker ps --filter "name=evocode-redis" --format "{{.Names}}" 2>$null
    Check 'PG 容器 evocode-postgres' ($pg -match 'evocode-postgres') 'docker compose up -d postgres'
    Check 'Redis 容器 evocode-redis' ($redis -match 'evocode-redis') 'docker compose up -d redis'
}

# 2. 端口
Write-Host '--- 2. 端口 ---' -ForegroundColor Yellow
$portHint = @{
    5432 = 'docker compose up -d postgres（引擎就绪后）'
    6380 = 'docker compose up -d redis'
    18080 = 'backend 未启动：引擎就绪后跑 scripts\start-dev.bat（或 java -jar backend\target\...jar）'
    8081  = 'analyzer 未启动：同 start-dev.bat'
    5173  = 'frontend 未启动：同 start-dev.bat'
}
foreach ($p in 5432, 6380, 18080, 8081, 5173) {
    $r = Test-NetConnection 127.0.0.1 -Port $p -WarningAction SilentlyContinue
    $label = switch ($p) { 5432 { 'PG' } 6380 { 'Redis' } 18080 { 'backend' } 8081 { 'analyzer' } 5173 { 'frontend' } }
    Check ("端口 {0} ({1})" -f $p, $label) $r.TcpTestSucceeded $portHint[$p]
}

# 3. 依赖与构建产物
Write-Host '--- 3. 依赖/产物 ---' -ForegroundColor Yellow
Check 'backend jar 存在' (Test-Path "$root\backend\target\evocode-backend-0.1.0-SNAPSHOT.jar") '需先跑 start-dev.bat（自动 mvn package）或 cd backend; .\mvnw.cmd -q -DskipTests package'
Check 'analyzer .venv 存在' (Test-Path "$root\analyzer\.venv\Scripts\python.exe") 'cd analyzer; python -m venv .venv; .venv\Scripts\pip install -r requirements.txt'
Check 'frontend node_modules 存在' (Test-Path "$root\frontend\node_modules") 'cd frontend; npm install'

# 4. 配置
Write-Host '--- 4. 配置（根 .env）---' -ForegroundColor Yellow
$envFile = "$root\.env"
if (Test-Path $envFile) {
    $envContent = Get-Content $envFile -Raw
    Check 'LLM_API_KEY 已配置' ($envContent -match 'LLM_API_KEY\s*=\s*\S') '可选：不配则 AI 医生/文档降级（报告走规则版）'
    Check 'ANALYZER_PG_DSN 已配置' ($envContent -match 'ANALYZER_PG_DSN\s*=\s*\S') '可选：start-dev.bat 有内置默认，改过 DB 密码才需要'
} else {
    Write-Host '[INFO] 根 .env 不存在（可选配置；复制 .env.example 即可）' -ForegroundColor DarkGray
}

# 5. 汇总
Write-Host '==== 汇总 ====' -ForegroundColor Cyan
if ($issues.Count -eq 0) {
    Write-Host '全部就绪。启动：双击 scripts\start-dev.bat，打开 http://localhost:5173' -ForegroundColor Green
} else {
    Write-Host "发现 $($issues.Count) 个问题：" -ForegroundColor Red
    $issues | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    Write-Host '修复后重跑本脚本；仍失败请贴出 scripts\start-dev.bat 主窗口与 backend 窗口的输出。'
}
