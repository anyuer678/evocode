"""重复代码检测测试：同文件重复、跨文件重复、干净代码。"""

from pathlib import Path

from app.core.duplication_scan import duplication_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


_SNIPPET = "\n".join(
    "    result = process_item(item, config, cache)"
    for _ in range(8)
)


def test_same_file_duplication_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": _SNIPPET + "\n\n" + _SNIPPET,
    })
    issues = [i for i in duplication_scan(code) if i["ruleKey"] == "DUPLICATED-BLOCK"]
    assert issues
    assert "重复" in issues[0]["message"]
    assert "抽取" in issues[0]["suggestion"]


def test_cross_file_duplication_detected(tmp_path):
    code = _tree(tmp_path, {
        "a.py": _SNIPPET,
        "b.py": _SNIPPET,
    })
    issues = [i for i in duplication_scan(code) if i["ruleKey"] == "DUPLICATED-BLOCK"]
    cross = [i for i in issues if "跨文件" in i["message"]]
    assert cross
    assert "a.py" in cross[0]["filePath"] or "b.py" in cross[0]["filePath"]


def test_clean_code_no_duplication(tmp_path):
    code = _tree(tmp_path, {
        "a.py": "def f(x):\n    return x + 1\n",
        "b.py": "def g(y):\n    return y * 2\n",
    })
    assert duplication_scan(code) == []


def test_whitespace_difference_not_false_positive(tmp_path):
    # 仅缩进不同不算重复（归一化后相同会被检测到——这里验证归一化确实生效）
    code = _tree(tmp_path, {
        "a.py": _SNIPPET.replace("    ", "    "),  # 同一缩进
        "b.py": _SNIPPET.replace("    ", "  "),    # 不同缩进（归一化后相同）
    })
    issues = [i for i in duplication_scan(code) if i["ruleKey"] == "DUPLICATED-BLOCK"]
    # 归一化后应跨文件检出
    assert any("跨文件" in i["message"] for i in issues)
