"""魔法数字扫描测试：检出裸数字、过滤常见值/声明赋值、干净代码。"""

from pathlib import Path

from app.core.magicnumber_scan import magicnumber_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


def test_magic_number_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "if retries > 300:\n    fail()\n",
    })
    issues = [i for i in magicnumber_scan(code) if i["ruleKey"] == "MAGIC-NUMBER"]
    assert issues
    assert "300" in issues[0]["message"]
    assert "命名常量" in issues[0]["suggestion"]


def test_common_values_filtered(tmp_path):
    # 100/1000/1024 等常见比率/尺寸值不报
    code = _tree(tmp_path, {
        "a.py": "ratio = 100\nsize = 1024\nx = 1000\n",
    })
    assert magicnumber_scan(code) == []


def test_declaration_assignment_not_reported(tmp_path):
    # `maxRetries = 300` 是有意配置，不报
    code = _tree(tmp_path, {
        "a.js": "const maxRetries = 300;\n",
    })
    assert magicnumber_scan(code) == []


def test_clean_code_no_magic(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "def f(x):\n    return x + 1\n",
    })
    assert magicnumber_scan(code) == []
