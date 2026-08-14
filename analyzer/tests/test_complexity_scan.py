"""复杂度扫描测试：高复杂度函数标记、简单函数忽略。"""

from pathlib import Path

from app.core.complexity_scan import complexity_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


def test_high_complexity_function_detected(tmp_path):
    code = _tree(tmp_path, {
        "service.py": (
            "def process(x):\n"
            "    if x > 0:\n"
            "        for i in range(10):\n"
            "            if i % 2:\n"
            "                while x:\n"
            "                    if x > 5:\n"
            "                        x -= 1\n"
            "                    elif x > 3:\n"
            "                        x -= 2\n"
            "                    else:\n"
            "                        x -= 3\n"
            "            elif i % 3:\n"
            "                x += 1\n"
            "            else:\n"
            "                x += 2\n"
            "    elif x < 0:\n"
            "        x = 0\n"
            "    else:\n"
            "        x = 1\n"
            "    return x\n"
        ),
    })
    issues = complexity_scan(code)
    hits = [i for i in issues if i["ruleKey"] == "COMPLEX-FUNCTION"]
    assert hits
    assert "process" in hits[0]["message"]
    assert "拆分" in hits[0]["suggestion"]
    assert hits[0]["filePath"] == "service.py"


def test_simple_function_ignored(tmp_path):
    code = _tree(tmp_path, {
        "simple.py": "def add(a, b):\n    return a + b\n",
    })
    assert complexity_scan(code) == []


def test_java_high_complexity(tmp_path):
    code = _tree(tmp_path, {
        "A.java": (
            "class A {\n"
            "  int f(int x) {\n"
            "    if (x > 0) { if (x > 5) { if (x > 9) { return 1; } return 2; } return 3; }\n"  # noqa: E501
            "    for (int i = 0; i < x; i++) { if (i > 3) { return 4; } }\n"
            "    while (x > 0) { if (x > 8) { return 5; } x--; }\n"
            "    switch (x) { case 1: return 6; case 2: return 7; default: return 8; }\n"  # noqa: E501
            "    return 0;\n"
            "  }\n"
            "}\n"
        ),
    })
    issues = complexity_scan(code)
    hits = [i for i in issues if i["ruleKey"] == "COMPLEX-FUNCTION"]
    assert hits
    assert "f" in hits[0]["message"]
