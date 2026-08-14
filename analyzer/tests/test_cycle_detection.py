"""架构环依赖检测测试（T-ARCH-CYCLE）。"""

from app.core.arch.base import ArchEdge, check_cycles


def test_no_cycle_returns_empty():
    edges = [
        ArchEdge(source="a", target="b"),
        ArchEdge(source="b", target="c"),
        ArchEdge(source="c", target="d"),
    ]
    assert check_cycles(edges) == []


def test_simple_cycle_detected():
    edges = [
        ArchEdge(source="a", target="b"),
        ArchEdge(source="b", target="c"),
        ArchEdge(source="c", target="a"),
    ]
    violations = check_cycles(edges)
    assert len(violations) == 1
    v = violations[0]
    assert v.violation_type == "CYCLE"
    assert v.severity == "MAJOR"
    assert "a" in v.description and "b" in v.description and "c" in v.description
    # 建议应包含打破环的具体做法
    assert "打破环" in v.suggestion


def test_two_cycles_both_detected():
    edges = [
        ArchEdge(source="a", target="b"),
        ArchEdge(source="b", target="a"),
        ArchEdge(source="x", target="y"),
        ArchEdge(source="y", target="z"),
        ArchEdge(source="z", target="x"),
    ]
    violations = check_cycles(edges)
    assert len(violations) == 2


def test_self_loop_reported_as_cycle():
    # 自环（A→A）是自依赖问题，应报告
    edges = [ArchEdge(source="a", target="a")]
    violations = check_cycles(edges)
    assert len(violations) == 1
    assert violations[0].violation_type == "CYCLE"
    assert "自依赖" in violations[0].description


def test_dag_with_back_edge_to_entry():
    # a→b→c→b 中 b→c→b 是环
    edges = [
        ArchEdge(source="a", target="b"),
        ArchEdge(source="b", target="c"),
        ArchEdge(source="c", target="b"),
    ]
    violations = check_cycles(edges)
    assert len(violations) == 1
    # description 形如 "b → c → b"
    assert "b → c → b" in violations[0].description
