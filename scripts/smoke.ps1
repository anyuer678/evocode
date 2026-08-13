# EvoCode 冒烟检查（win）：三端健康检查
# 用法：.\scripts\smoke.ps1        （默认 8091/18080/5173，与 start-dev.bat 端口约定一致）
# 可覆盖：$env:SMOKE_ANALYZER_URL / $env:SMOKE_BACKEND_URL / $env:SMOKE_FRONTEND_URL
$ErrorActionPreference = 'Stop'
$targets = @(
    @{ Name = 'analyzer';  Url = if ($env:SMOKE_ANALYZER_URL) { $env:SMOKE_ANALYZER_URL } else { 'http://127.0.0.1:8091/health' } },
    @{ Name = 'backend';   Url = if ($env:SMOKE_BACKEND_URL) { $env:SMOKE_BACKEND_URL } else { 'http://127.0.0.1:18080/api/v1/health' } },
    @{ Name = 'frontend';  Url = if ($env:SMOKE_FRONTEND_URL) { $env:SMOKE_FRONTEND_URL } else { 'http://127.0.0.1:5173/' } }
)
$fail = $false
foreach ($t in $targets) {
    try {
        $r = Invoke-WebRequest -Uri $t.Url -TimeoutSec 5 -UseBasicParsing
        Write-Host ("[{0}] OK  {1}  ->  HTTP {2}" -f $t.Name, $t.Url, $r.StatusCode) -ForegroundColor Green
    } catch {
        $fail = $true
        Write-Host ("[{0}] FAIL  {1}  ->  {2}" -f $t.Name, $t.Url, $_.Exception.Message) -ForegroundColor Red
    }
}
if ($fail) { exit 1 } else { Write-Host '冒烟通过：三端可达' -ForegroundColor Green }
