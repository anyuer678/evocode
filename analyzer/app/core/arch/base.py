"""架构分析数据结构（docs/06-API契约.md §5.5）。

节点以 node_key（文件内唯一符号）标识，端点返回 key 而非 DB id；
落库（V003）时由 backend 分配自增 id 并关联。
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ArchNode:
    node_key: str  # 全局唯一：类名 / 文件名:顶层函数名
    name: str
    node_type: str  # CONTROLLER/SERVICE/REPOSITORY/ENTITY/UTIL/MODULE/OTHER
    file_path: str
    line: int = 0


@dataclass
class ArchEdge:
    source: str  # node_key
    target: str
    relation: str = "CALL"


@dataclass
class ArchViolation:
    violation_type: str  # LAYER_VIOLATION / CYCLE / ...
    description: str
    severity: str
    suggestion: str
    source: str | None = None
    target: str | None = None


@dataclass
class ArchResult:
    nodes: list[ArchNode] = field(default_factory=list)
    edges: list[ArchEdge] = field(default_factory=list)
    violations: list[ArchViolation] = field(default_factory=list)


# ---- 节点类型推断（按命名约定，v0.2 基础版） ----
TYPE_RULES: dict[str, tuple[str, ...]] = {
    "CONTROLLER": ("controller", "view", "api"),
    "SERVICE": ("service",),
    "REPOSITORY": ("repository", "repos", "dao", "mapper", "store"),
    "ENTITY": ("entity", "model", "domain", "dto", "vo", "po", "schema"),
    "UTIL": ("util", "helper", "common", "support", "config"),
}


def infer_node_type(name: str, file_name: str = "") -> str:
    """按类名/文件名后缀推断分层类型；无法识别 → OTHER。"""
    lowered = (name + " " + file_name).lower()
    for ntype, keywords in TYPE_RULES.items():
        for kw in keywords:
            if kw in lowered:
                return ntype
    return "OTHER"


# 分层约束：禁止 "越过中间层" 的直接调用
# CONTROLLER →(应经)→ SERVICE →(应经)→ REPOSITORY → ENTITY
_VIOLATIONS: dict[tuple[str, str], tuple[str, str]] = {
    ("CONTROLLER", "REPOSITORY"): (
        "HIGH",
        "Controller 直接调用 Repository，违反分层，应经 Service",
    ),
    ("CONTROLLER", "ENTITY"): (
        "MEDIUM",
        "Controller 直接使用实体，建议经 Service 封装",
    ),
    ("SERVICE", "ENTITY"): ("MEDIUM", "Service 直接操作实体，建议经 Repository 访问"),
}


def check_layer_violations(
    nodes: dict[str, ArchNode], edges: list[ArchEdge]
) -> list[ArchViolation]:
    violations: list[ArchViolation] = []
    for edge in edges:
        src = nodes.get(edge.source)
        dst = nodes.get(edge.target)
        if src is None or dst is None:
            continue
        rule = _VIOLATIONS.get((src.node_type, dst.node_type))
        if rule:
            severity, desc = rule
            violations.append(
                ArchViolation(
                    violation_type="LAYER_VIOLATION",
                    description=f"{desc}（{src.name} → {dst.name}）",
                    severity=severity,
                    suggestion="将数据访问迁移到下层模块，只调用相邻层",
                    source=src.node_key,
                    target=dst.node_key,
                )
            )
    return violations


def node_metrics(nodes: list[ArchNode], edges: list[ArchEdge]) -> dict[str, dict]:
    """节点出入度指标：{node_key: {inDegree, outDegree, depCount}}。"""
    out_deg: dict[str, int] = {}
    in_deg: dict[str, int] = {}
    for e in edges:
        out_deg[e.source] = out_deg.get(e.source, 0) + 1
        in_deg[e.target] = in_deg.get(e.target, 0) + 1
    return {
        n.node_key: {
            "inDegree": in_deg.get(n.node_key, 0),
            "outDegree": out_deg.get(n.node_key, 0),
            # 审查修复：depCount 应为该节点依赖的模块数（出边数），此前误用节点总数
            "depCount": out_deg.get(n.node_key, 0),
        }
        for n in nodes
    }
