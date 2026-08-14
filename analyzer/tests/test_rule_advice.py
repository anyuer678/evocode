"""规则建议引擎测试：命中/未命中/兜底三类。"""

from app.core.rule_advice import advice_for


def test_hit_rule_returns_impact_and_fix():
    impact, fix = advice_for("java:S107", "src/A.java", "Method has 8 parameters")
    assert "参数过多" in impact
    assert "参数对象" in fix


def test_unmatched_rule_falls_back_with_location():
    impact, fix = advice_for("java:S9999", "src/B.java", "some message")
    # 兜底：影响带位置与消息，避免空泛
    assert "src/B.java" in impact
    assert "some message" in impact
    assert "定位" in fix


def test_rule_advice_no_crash_on_empty():
    impact, fix = advice_for("", "", "")
    assert impact
    assert fix


def test_java_s106_system_out():
    impact, fix = advice_for("java:S106", "src/C.java", "Use logger instead")
    assert "System.out" in impact
    assert "logger" in fix.lower()


def test_prefix_matches_longest():
    # 完整 key "java:S107" 应优先于语言前缀 "java:"
    impact, fix = advice_for("java:S107", "x", "m")
    assert "参数" in impact


def test_typescript_s107():
    impact, fix = advice_for("typescript:S107", "src/a.ts", "too many params")
    assert "参数" in impact
