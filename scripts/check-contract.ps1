# T-C-01: analyzer schemas vs backend DTO field consistency (analyzer fields must be subset of backend fields).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/check-contract.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$analyzer = Join-Path $root "analyzer"
$venvPy = Join-Path $analyzer ".venv\Scripts\python.exe"

function Get-RecordComponents([string]$file) {
    $content = Get-Content -LiteralPath $file -Raw
    if ($content -notmatch 'record\s+\w+\(([^)]*)\)') { throw "record header not found: $file" }
    $args = $Matches[1] -split "," | ForEach-Object {
        ($_ -split "=")[0].Trim() -split "\s+" | Select-Object -Last 1
    }
    return @($args | Where-Object { $_ })
}

$tmp = Join-Path $env:TEMP ("contract-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Push-Location $analyzer
    $schemaJson = & $venvPy -c "from app.schemas import ScanResult, ScanFile; import json; print(json.dumps({'ScanResult': list(ScanResult.model_fields.keys()), 'ScanFile': list(ScanFile.model_fields.keys())}))"
    if ($LASTEXITCODE -ne 0) { throw "analyzer schema export failed" }
    Pop-Location
    $analyzerFields = $schemaJson | ConvertFrom-Json

    $backendResult = @(Get-RecordComponents (Join-Path $root "backend\src\main\java\com\evocode\dto\scan\ScanResultResp.java"))
    $backendFile = @(Get-RecordComponents (Join-Path $root "backend\src\main\java\com\evocode\dto\scan\ScanFileResp.java"))

    $missing = @()
    foreach ($f in $analyzerFields.ScanResult) { if ($f -notin $backendResult) { $missing += "ScanResult.$f" } }
    foreach ($f in $analyzerFields.ScanFile) { if ($f -notin $backendFile) { $missing += "ScanFile.$f" } }

    if ($missing.Count -gt 0) {
        Write-Host ("FAIL: analyzer fields missing in backend DTOs: {0}" -f ($missing -join ", "))
        exit 1
    }
    Write-Host ("PASS: contract fields consistent (ScanResult {0} fields, ScanFile {1} fields)" -f $backendResult.Count, $backendFile.Count)
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
