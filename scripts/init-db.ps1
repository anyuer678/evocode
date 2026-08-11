# EvoCode 数据库初始化（win）：按序执行 db/migration/V*.sql
# 依赖：docker compose up -d postgres（容器 evocode-postgres）
# 用法：.\scripts\init-db.ps1
$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $PSScriptRoot
$migDir = Join-Path $root 'db\migration'
$container = 'evocode-postgres'

# 1. 读取 .env（若存在）
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]*)=(.*)$') { Set-Item -Path "env:$($matches[1].Trim())" -Value $matches[2].Trim() }
    }
}
$db   = if ($env:POSTGRES_DB)   { $env:POSTGRES_DB }   else { 'evocode' }
$user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'evocode' }

# 2. 等待 postgres 就绪
Write-Host '等待 postgres 就绪...' -ForegroundColor Cyan
docker exec $container pg_isready -U $user -d $db *> $null
while ($LASTEXITCODE -ne 0) { Start-Sleep -Seconds 2; docker exec $container pg_isready -U $user -d $db *> $null }
Write-Host 'postgres 就绪' -ForegroundColor Green

# 3. 建版本跟踪表
@"
CREATE TABLE IF NOT EXISTS schema_version (
  version    VARCHAR(50) PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
"@ | docker exec -i $container psql -U $user -d $db -v ON_ERROR_STOP=1 2>$null
if ($LASTEXITCODE -ne 0) { throw '初始化 schema_version 失败' }

# 4. 按序执行未应用迁移
$applied = docker exec -i $container psql -U $user -d $db -t -A -c "SELECT version FROM schema_version ORDER BY version" 2>$null
$appliedSet = @()
if ($applied) {
    $appliedSet = @($applied.Trim().Split("`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

Get-ChildItem $migDir -Filter 'V*.sql' | Sort-Object Name | ForEach-Object {
    $ver = $_.BaseName
    if ($ver -in $appliedSet) { Write-Host "跳过（已应用）：$ver" -ForegroundColor DarkGray; return }
    Write-Host "应用迁移：$ver ..." -ForegroundColor Cyan
    # docker cp 进容器再执行（彻底绕开 PS 文本管道的中文/编码问题）
    docker cp $_.FullName "${container}:/tmp/${ver}.sql"
    if ($LASTEXITCODE -ne 0) { throw "复制迁移文件失败：$ver" }
    docker exec -i $container psql -U $user -d $db -v ON_ERROR_STOP=1 -f "/tmp/${ver}.sql"
    if ($LASTEXITCODE -ne 0) { throw "迁移失败：$ver" }
    docker exec -i $container psql -U $user -d $db -q -c "INSERT INTO schema_version(version) VALUES ('$ver')" | Out-Null
    Write-Host "完成：$ver" -ForegroundColor Green
}

Write-Host '数据库初始化完成' -ForegroundColor Green
