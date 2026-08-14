"""超大方法/类/文件扫描测试。"""

from pathlib import Path

from app.core.bloated_scan import bloated_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


def test_giant_python_method_detected(tmp_path):
    body = "\n".join(f"    x{i} = {i}" for i in range(160))
    code = _tree(tmp_path, {
        "big.py": "def huge():\n" + body + "\n    return 0\n",
    })
    issues = [i for i in bloated_scan(code) if i["ruleKey"] == "BLOATED-METHOD"]
    assert issues
    assert "huge" in issues[0]["message"]
    assert "拆分" in issues[0]["suggestion"]


def test_java_giant_class_detected(tmp_path):
    body = "\n".join(f"  int x{i} = {i};" for i in range(520))
    code = _tree(tmp_path, {
        "God.java": "class God {\n" + body + "\n}\n",
    })
    issues = [i for i in bloated_scan(code) if i["ruleKey"] == "BLOATED-CLASS"]
    assert issues
    assert "God" in issues[0]["message"]


def test_small_code_no_issues(tmp_path):
    code = _tree(tmp_path, {
        "ok.py": "def f(x):\n    return x + 1\n",
    })
    assert bloated_scan(code) == []


def test_giant_file_detected(tmp_path):
    body = "\n".join(f"x{i} = {i}" for i in range(1300))
    code = _tree(tmp_path, {
        "huge.py": body + "\n",
    })
    issues = [i for i in bloated_scan(code) if i["ruleKey"] == "BLOATED-FILE"]
    assert issues
