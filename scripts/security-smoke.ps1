# EvoCode 安全冒烟（win）：覆盖《08》§6 可自动化子集（兼容 Windows PowerShell 5.1）
# 用法：
#   .\scripts\security-smoke.ps1            # 仅静态项（T-S-05 无执行路径 / T-S-08 端口暴露）
#   .\scripts\security-smoke.ps1 -Dynamic   # 追加动态项（T-S-07 排序注入 / T-S-01 恶意 zip），需 backend 运行
# 可覆盖：$env:SMOKE_BACKEND_URL
param([switch]$Dynamic)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backendUrl = if ($env:SMOKE_BACKEND_URL) { $env:SMOKE_BACKEND_URL } else { 'http://127.0.0.1:18080' }
$fail = $false

function Check([string]$name, [scriptblock]$body) {
    try {
        & $body
        Write-Host ("[PASS] {0}" -f $name) -ForegroundColor Green
    } catch {
        $fail = $true
        Write-Host ("[FAIL] {0} -> {1}" -f $name, $_.Exception.Message) -ForegroundColor Red
    }
}

Write-Host '=== 静态安全项 ===' -ForegroundColor Cyan

# T-S-05 无执行路径：analyzer 白名单 subprocess = sonar.py（Sonar 可选）+ gitlog.py（系统 git 演化统计），
# 其余 eval/exec/subprocess/os.system 调用即失败
Check 'T-S-05 无执行路径（eval/exec/subprocess 白名单）' {
    $hits = Get-ChildItem (Join-Path $root 'analyzer\app') -Recurse -Filter *.py |
        Select-String -Pattern '\beval\s*\(|\bexec\s*\(|\bos\.system\s*\(|\bsubprocess\.(run|Popen|call|check_output)\s*\(' |
        Where-Object { $_.Path -notmatch 'sonar\.py' -and $_.Path -notmatch 'gitlog\.py' }
    if ($hits) {
        $files = ($hits.Path | Select-Object -Unique) -join ', '
        throw "发现非白名单执行调用: $files"
    }
}

# T-S-08 端口暴露：EvoCode 关键端口（postgres 5432 / redis 6380 / sonar 9000）
# 本地地址为 0.0.0.0 或 [::]（绑所有接口）才视为暴露；127.0.0.1 绑定安全
Check 'T-S-08 关键端口仅绑 127.0.0.1' {
    $bad = netstat -ano | Select-String -Pattern 'LISTENING' |
        Where-Object { $_ -match '^\s*TCP\s+0\.0\.0\.0:(5432|6380|9000)\s' -or $_ -match '^\s*TCP\s+\[::\]:(5432|6380|9000)\s' }
    foreach ($l in $bad) {
        throw '端口暴露到非本机: ' + $l.Line.Trim()
    }
}

if ($Dynamic) {
    Write-Host '=== 动态安全项（需 backend）===' -ForegroundColor Cyan
    try {
        Invoke-WebRequest -Uri "$backendUrl/api/v1/health" -TimeoutSec 5 -UseBasicParsing | Out-Null
    } catch {
        Write-Host '[SKIP] backend 未运行，跳过动态项（start-dev.bat 起服务后 -Dynamic 重跑）' -ForegroundColor Yellow
        exit 0
    }

    # T-S-07 排序注入：sort 非白名单 → 业务码 1002（PS5.1 从 4xx 异常读响应体）
    Check 'T-S-07 排序注入（sort=malicious → 1002）' {
        $body = $null
        try {
            Invoke-WebRequest -Uri "$backendUrl/api/v1/projects?page=1&size=10&sort=malicious" `
                -TimeoutSec 5 -UseBasicParsing | Out-Null
        } catch {
            $resp = $_.Exception.Response
            if ($null -eq $resp) { throw "请求失败: $($_.Exception.Message)" }
            $stream = $resp.GetResponseStream()
            $reader = [System.IO.StreamReader]::new($stream)
            $body = $reader.ReadToEnd() | ConvertFrom-Json
        }
        if ($null -eq $body -or $body.code -ne 1002) {
            throw "期望 code=1002，实际 $($body.code)"
        }
    }

    # T-S-01 恶意 zip（路径穿越，AC-8）：构造含 ../evil 条目的 zip 上传 → 拒绝 2003
    Check 'T-S-01 zip 路径穿越（../evil → 2003）' {
        $tmp = Join-Path $env:TEMP ("evil-" + [guid]::NewGuid().ToString('N') + ".zip")
        Add-Type -AssemblyName System.IO.Compression
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::Open($tmp, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $zip.CreateEntry('../evil.txt')
            $writer = [System.IO.StreamWriter]::new($entry.Open())
            $writer.Write('evil'); $writer.Dispose()
        } finally { $zip.Dispose() }
        try {
            $raw = curl.exe -s -X POST "$backendUrl/api/v1/projects" `
                -F "name=evil-zip" -F "file=@$tmp"
            $body = $raw | ConvertFrom-Json
            if ($body.code -ne 2003) { throw "期望 code=2003（FILE_ILLEGAL），实际 $($body.code)" }
        } finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    }
}

if ($fail) {
    Write-Host '安全冒烟未通过' -ForegroundColor Red
    exit 1
} else {
    Write-Host '安全冒烟通过' -ForegroundColor Green
}
