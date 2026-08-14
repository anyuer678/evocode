"""遗留标记扫描测试：TODO/FIXME 检出、非注释不报、HACK 建议。"""

from pathlib import Path

from app.core.todomarker_scan import todomarker_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


def test_todo_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "# TODO: 补充超时处理\nx = 1\n",
    })
    issues = [i for i in todomarker_scan(code) if i["ruleKey"] == "LEFTOVER-TODO"]
    assert issues
    assert "遗留" in issues[0]["message"]
    assert issues[0]["severity"] == "MINOR"


def test_fixme_detected_as_major(tmp_path):
    code = _tree(tmp_path, {
        "A.java": "// FIXME: 这里会 NPE\nint x = 1;\n",
    })
    issues = [i for i in todomarker_scan(code) if i["ruleKey"] == "LEFTOVER-FIXME"]
    assert issues
    assert issues[0]["severity"] == "MAJOR"


def test_marker_in_string_not_reported(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "msg = 'TODO: not a comment'\nx = 1\n",
    })
    assert todomarker_scan(code) == []


def test_clean_code_no_markers(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "def f(x):\n    return x\n",
    })
    assert todomarker_scan(code) == []


def test_inline_todo_detected(tmp_path):
    # 行尾 TODO（最常见位置）应检出
    code = _tree(tmp_path, {
        "a.js": "const x = doIt(); // TODO: handle error\n",
    })
    issues = [i for i in todomarker_scan(code) if i["ruleKey"] == "LEFTOVER-TODO"]
    assert issues
    assert "handle error" in issues[0]["message"]
