"""错误处理反模式测试：Python pass / Java 空 catch / 干净代码。"""

from pathlib import Path

from app.core.errorhandling_scan import errorhandling_scan


def _tree(tmp_path: Path, files: dict[str, str]) -> Path:
    for rel, content in files.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


def test_python_pass_only_detected(tmp_path):
    code = _tree(tmp_path, {
        "main.py": (
            "try:\n"
            "    do_something()\n"
            "except ValueError:\n"
            "    pass\n"
        ),
    })
    issues = [i for i in errorhandling_scan(code) if i["ruleKey"] == "SWALLOWED-EXCEPTION"]  # noqa: E501
    assert issues
    assert "静默" in issues[0]["message"]
    assert "logger" in issues[0]["suggestion"]


def test_python_logged_exception_not_reported(tmp_path):
    code = _tree(tmp_path, {
        "main.py": (
            "try:\n"
            "    do_something()\n"
            "except ValueError as e:\n"
            "    logger.exception('failed: %s', e)\n"
        ),
    })
    assert errorhandling_scan(code) == []


def test_java_empty_catch_detected(tmp_path):
    code = _tree(tmp_path, {
        "A.java": (
            "class A {\n"
            "  void f() {\n"
            "    try { doIt(); }\n"
            "    catch (Exception e) { }\n"
            "  }\n"
            "}\n"
        ),
    })
    issues = [i for i in errorhandling_scan(code) if i["ruleKey"] == "SWALLOWED-EXCEPTION"]  # noqa: E501
    assert issues
    assert "catch" in issues[0]["message"]


def test_java_catch_with_log_not_reported(tmp_path):
    code = _tree(tmp_path, {
        "A.java": (
            "class A {\n"
            "  void f() {\n"
            "    try { doIt(); }\n"
            "    catch (Exception e) { log.error(\"err\", e); }\n"
            "  }\n"
            "}\n"
        ),
    })
    assert errorhandling_scan(code) == []


def test_python_except_with_comment_still_reported(tmp_path):
    # 仅注释不算有效处理，仍应报告（避免"注释当处理"的伪装）
    code = _tree(tmp_path, {
        "main.py": (
            "try:\n"
            "    do_something()\n"
            "except ValueError:\n"
            "    # 忽略：非关键路径\n"
            "    pass\n"
        ),
    })
    issues = [i for i in errorhandling_scan(code) if i["ruleKey"] == "SWALLOWED-EXCEPTION"]  # noqa: E501
    assert issues
