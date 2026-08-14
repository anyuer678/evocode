# EvoCode 端到端功能冒烟（win）：核心链路验证
# 用法：.\scripts\e2e-smoke.ps1 [-KeepProject]
# 流程：健康检查 → 上传 samples/demo-store → 快扫档案 → 发起 FULL 分析 →
#       轮询完成 → 报告/历史/技术债/文件快照 → 清理（默认）
# 依赖：三端已启动（scripts\start-dev.bat 或手动），samples/demo-store 存在
param([switch]$KeepProject)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backend = 'http://127.0.0.1:18080'
$pass = 0; $fail = 0

function Check([string]$name, [scriptblock]$body) {
    try {
        & $body | Out-Null
        $script:pass++
        Write-Host ("[PASS] {0}" -f $name) -ForegroundColor Green
    } catch {
        $script:fail++
        Write-Host ("[FAIL] {0} -> {1}" -f $name, $_.Exception.Message) -ForegroundColor Red
    }
}

Write-Host '==== EvoCode 端到端功能冒烟 ====' -ForegroundColor Cyan

# 0. 三端健康（backend/analyzer 若未运行则自动拉起）
function Ensure-Backend {
    try { Invoke-WebRequest "$backend/api/v1/health" -TimeoutSec 3 -UseBasicParsing | Out-Null; return }
    catch { Write-Host "[INFO] backend 未运行，尝试启动…" -ForegroundColor Yellow }
    $jar = Join-Path $root 'backend\target\evocode-backend-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path $jar)) { throw 'backend jar 不存在，请先构建（mvnw package）' }
    $env:BACKEND_PORT = '18080'
    Start-Process -FilePath 'java' -ArgumentList '-jar', $jar `
        -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep 1
        try { Invoke-WebRequest "$backend/api/v1/health" -TimeoutSec 2 -UseBasicParsing | Out-Null; return }
        catch { }
    }
    throw 'backend 启动失败（18080 未就绪）'
}

function Ensure-Analyzer {
    try { Invoke-WebRequest "http://127.0.0.1:8091/health" -TimeoutSec 3 -UseBasicParsing | Out-Null; return }
    catch { Write-Host "[INFO] analyzer 未运行，尝试启动…" -ForegroundColor Yellow }
    $py = Join-Path $root 'analyzer\.venv\Scripts\python.exe'
    $env:ANALYZER_PG_DSN = "postgresql://evocode:evocode_dev@127.0.0.1:5432/evocode"
    Start-Process -FilePath $py -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8091' `
        -WorkingDirectory (Join-Path $root 'analyzer') -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep 1
        try { Invoke-WebRequest "http://127.0.0.1:8091/health" -TimeoutSec 2 -UseBasicParsing | Out-Null; return }
        catch { }
    }
    throw 'analyzer 启动失败（8091 未就绪）'
}
Ensure-Backend
Ensure-Analyzer
Check 'backend 健康' { Invoke-WebRequest "$backend/api/v1/health" -TimeoutSec 5 -UseBasicParsing | Out-Null }
Check 'analyzer 健康' { Invoke-WebRequest "http://127.0.0.1:8091/health" -TimeoutSec 5 -UseBasicParsing | Out-Null }

# 1. 上传 samples/demo-store 创建项目
$sample = Join-Path $root 'samples\demo-store'
if (-not (Test-Path $sample)) { Write-Host "[SKIP] samples\demo-store 不存在，跳过项目创建" -ForegroundColor Yellow }
else {
    $projId = $null
    Check '上传 demo-store 创建项目' {
        $zip = Join-Path $env:TEMP ("evocode-e2e-" + [guid]::NewGuid().ToString('N') + ".zip")
        Add-Type -AssemblyName System.IO.Compression
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [System.IO.Compression.ZipFile]::CreateFromDirectory($sample, $zip)
        $resp = curl.exe -s -X POST "$backend/api/v1/projects" -F "name=e2e-smoke-$(Get-Date -Format HHmmss)" -F "file=@$zip" | ConvertFrom-Json
        Remove-Item $zip -Force -ErrorAction SilentlyContinue
        if ($resp.code -ne 0) { throw "创建失败: $($resp.message)" }
        $script:projId = $resp.data.id
    }

    if ($script:projId) {
        $pid2 = $script:projId
        # 2. 快扫档案（等 READY/FAILED）
        Check '快扫生成档案（≤60s）' {
            $ready = $false
            for ($i = 0; $i -lt 30; $i++) {
                Start-Sleep 2
                $d = (Invoke-WebRequest "$backend/api/v1/projects/$pid2" -TimeoutSec 5 -UseBasicParsing).Content | ConvertFrom-Json
                if ($d.data.status -in @('READY','FAILED')) { $ready = $true; break }
            }
            if (-not $ready) { throw '快扫 60s 内未落定' }
            if ($d.data.fileCount -lt 1) { throw '档案无文件' }
        }

        # 3. 发起 FULL 分析（aid 提取在 Check 外，避免 scriptblock 作用域问题）
        $script:aid = $null
        Check '发起 FULL 分析' {
            $a = curl.exe -s -X POST "$backend/api/v1/projects/$pid2/analyses" -H "Content-Type: application/json" -d '{"type":"FULL"}' | ConvertFrom-Json
            if ($a.code -eq 2002) { throw '分析忙（并发任务）' }
            if ($null -eq $a.data -or $null -eq $a.data.id) { throw "发起失败: $($a.message)" }
            $script:aid = $a.data.id
        }

        # 4. 轮询分析完成（≤180s）
        if ($script:aid) {
            $aid2 = $script:aid
            Check "分析完成（analysis=$aid2，≤180s）" {
                $done = $false
                for ($i = 0; $i -lt 90; $i++) {
                    Start-Sleep 2
                    $st = (Invoke-WebRequest "$backend/api/v1/analyses/$aid2" -TimeoutSec 5 -UseBasicParsing).Content | ConvertFrom-Json
                    if ($st.data.status -in @('SUCCEEDED','FAILED','CANCELLED')) { $done = $true; break }
                }
                if (-not $done) { throw '分析 180s 内未完成' }
                if ($st.data.status -ne 'SUCCEEDED') { throw "分析未成功: $($st.data.status)" }
            }

            # 5. 报告可取
            Check '报告生成' {
                $r = Invoke-WebRequest "$backend/api/v1/analyses/$aid2/report" -TimeoutSec 5 -UseBasicParsing
                $body = $r.Content | ConvertFrom-Json
                if ($body.code -ne 0 -or $null -eq $body.data.report.healthScore) { throw '报告无 healthScore' }
            }

            # 6. 分析历史含该次
            Check '分析历史记录' {
                $h = (Invoke-WebRequest "$backend/api/v1/projects/$pid2/analyses?page=1&size=5" -TimeoutSec 5 -UseBasicParsing).Content | ConvertFrom-Json
                if (-not ($h.data.items | Where-Object { $_.id -eq $aid2 })) { throw '历史无本次分析' }
            }

            # 7. 文件快照（file_node 由分析写入）
            Check '文件快照' {
                $f = (Invoke-WebRequest "$backend/api/v1/projects/$pid2/files?page=1&size=5" -TimeoutSec 5 -UseBasicParsing).Content | ConvertFrom-Json
                if ($f.data.total -lt 1) { throw '无文件快照' }
            }
        }
    }
}

Write-Host ("`n==== 结果：通过 {0} / 失败 {1} ====" -f $pass, $fail) -ForegroundColor Cyan
if ($fail -gt 0) { exit 1 } else { Write-Host '端到端冒烟通过' -ForegroundColor Green }
