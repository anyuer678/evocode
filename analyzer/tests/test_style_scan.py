"""风格一致性测试：行尾空白、tab/space 混用、BOM、干净代码。"""

from pathlib import Path

from app.core.style_scan import style_scan


def _tree(tmp_path: Path, files: dict[str, str], bom: bool = False) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        text = ("\ufeff" if bom else "") + content
        p.write_text(text, encoding="utf-8")
    return tmp_path


def test_trailing_whitespace_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "x = 1  \ny = 2  \nz = 3  \n",
    })
    issues = [i for i in style_scan(code) if i["ruleKey"] == "TRAILING-WHITESPACE"]
    assert issues
    assert "行尾" in issues[0]["message"]
    assert "trim" in issues[0]["suggestion"].lower()


def test_bom_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "x = 1\n",
    }, bom=True)
    issues = [i for i in style_scan(code) if i["ruleKey"] == "UTF8-BOM"]
    assert issues
    assert "BOM" in issues[0]["message"]


def test_clean_code_no_style_issues(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "x = 1\ny = 2\n",
    })
    assert style_scan(code) == []


def test_mixed_indent_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "\tdef f():\n\t    return 1\n",
    })
    issues = [i for i in style_scan(code) if i["ruleKey"] == "MIXED-INDENT"]
    assert issues
    assert "tab" in issues[0]["suggestion"].lower() or "空格" in issues[0]["suggestion"]
