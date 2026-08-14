"""安全扫描测试：硬编码密钥 / 危险调用 / SQL 拼接。"""

from pathlib import Path

from app.core.security_scan import security_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


def test_hardcoded_secret_detected(tmp_path):
    code = _tree(tmp_path, {
        "src/Config.java": 'class C { String apiKey = "sk-abc1234567890"; }',
    })
    issues = security_scan(code)
    hits = [i for i in issues if i["ruleKey"] == "SECRET-HARDCODED"]
    assert hits
    assert hits[0]["severity"] == "CRITICAL"
    assert hits[0]["filePath"] == "src/Config.java"


def test_dangerous_call_detected(tmp_path):
    code = _tree(tmp_path, {
        "main.py": "import os\nos.system('rm -rf /tmp/x')",
    })
    issues = security_scan(code)
    hits = [i for i in issues if i["ruleKey"] == "DANGEROUS-CALL"]
    assert hits
    assert "subprocess" in hits[0]["suggestion"]


def test_sql_concat_detected(tmp_path):
    code = _tree(tmp_path, {
        "repo.py": "q = 'SELECT * FROM u WHERE id=' + uid",
    })
    issues = security_scan(code)
    hits = [i for i in issues if i["ruleKey"] == "SQL-INJECTION"]
    assert hits
    assert "参数化" in hits[0]["suggestion"]


def test_node_modules_skipped(tmp_path):
    code = _tree(tmp_path, {
        "node_modules/x/index.js": 'const pwd = "should-not-scan-123456";',
        "src/index.js": 'const pwd = "should-scan-123456";',
    })
    issues = security_scan(code)
    hits = [i for i in issues if i["ruleKey"] == "SECRET-HARDCODED"]
    assert len(hits) == 1
    assert hits[0]["filePath"] == "src/index.js"


def test_clean_code_no_issues(tmp_path):
    code = _tree(tmp_path, {
        "ok.py": "def f(x):\n    return x + 1\n",
    })
    assert security_scan(code) == []
