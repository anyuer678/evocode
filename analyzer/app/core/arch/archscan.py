"""架构分析编排（docs/06-API契约.md §5.5）。

遍历代码目录 → 按语言分派 parser → 聚合节点/边（去重）→ 分层违规检测 → 节点指标。
产物为契约结构（nodeKey 标识），落库（V003）由 backend 完成。
"""

from __future__ import annotations

import logging
from pathlib import Path

from .base import ArchEdge, check_layer_violations, node_metrics
from .java_parser import parse_java_file
from .python_parser import parse_python_file

logger = logging.getLogger("evocode.analyzer.arch")

_SKIP_DIRS = {
    ".git",
    "node_modules",
    ".venv",
    "venv",
    "__pycache__",
    "dist",
    "build",
    "target",
    ".idea",
    ".vscode",
}
_PY_EXTS = {".py"}
_JAVA_EXTS = {".java"}


def _iter_source_files(code_dir: Path):
    for path in code_dir.rglob("*"):
        if path.is_dir():
            continue
        parts = set(path.parts)
        if parts & _SKIP_DIRS:
            continue
        if path.suffix in _PY_EXTS or path.suffix in _JAVA_EXTS:
            yield path


def architecture_scan(code_dir: str, languages: list[str] | None = None) -> dict:
    """扫描项目架构，返回契约结构 dict（06 §5.5）。"""
    root = Path(code_dir)
    if not root.is_dir():
        raise FileNotFoundError(f"codeDir not found: {code_dir}")

    nodes: list = []
    all_calls: list[tuple[str, list[str]]] = []
    for path in _iter_source_files(root):
        rel = str(path.relative_to(root)).replace("\\", "/")
        try:
            source = path.read_bytes()
            if path.suffix in _PY_EXTS:
                file_nodes, calls = parse_python_file(rel, source)
            else:
                file_nodes, calls = parse_java_file(rel, source)
        except Exception as exc:
            logger.warning("解析失败跳过 %s：%s", rel, exc)
            continue
        nodes.extend(file_nodes)
        all_calls.extend(calls)

    # 去重：节点按 node_key、边按 (source, target)
    seen_nodes: dict[str, object] = {}
    for n in nodes:
        seen_nodes.setdefault(n.node_key, n)
    nodes = list(seen_nodes.values())

    # 全局匹配调用边：候选调用名 ↔ 全局节点 key（忽略大小写/下划线），排除自调用
    node_keys = {n.node_key for n in nodes}
    norm = {k.lower().replace("_", "") for k in node_keys}
    edges: list[ArchEdge] = []
    for caller, names in all_calls:
        for name in names:
            key = name.lower().replace("_", "")
            if key in norm:
                target = next(k for k in node_keys if k.lower().replace("_", "") == key)
                if target != caller:
                    edges.append(ArchEdge(source=caller, target=target))

    seen_edges: set[tuple[str, str]] = set()
    unique_edges: list[ArchEdge] = []
    for e in edges:
        key = (e.source, e.target)
        if key not in seen_edges:
            seen_edges.add(key)
            unique_edges.append(e)

    node_map = {n.node_key: n for n in nodes}
    violations = check_layer_violations(node_map, unique_edges)
    metrics = node_metrics(nodes, unique_edges)

    return {
        "nodes": [
            {
                "nodeKey": n.node_key,
                "name": n.name,
                "nodeType": n.node_type,
                "filePath": n.file_path,
                "metrics": metrics.get(n.node_key, {}),
            }
            for n in nodes
        ],
        "edges": [
            {
                "sourceNodeKey": e.source,
                "targetNodeKey": e.target,
                "relation": e.relation,
            }
            for e in unique_edges
        ],
        "violations": [
            {
                "violationType": v.violation_type,
                "description": v.description,
                "sourceNodeKey": v.source,
                "targetNodeKey": v.target,
                "severity": v.severity,
                "suggestion": v.suggestion,
            }
            for v in violations
        ],
    }
