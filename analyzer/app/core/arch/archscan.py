"""架构分析编排（docs/06-API契约.md §5.5）。

遍历代码目录 → 按语言分派 parser → 聚合节点/边（去重）→ 分层违规检测 → 节点指标。
产物为契约结构（nodeKey 标识），落库（V003）由 backend 完成。
"""

from __future__ import annotations

import logging
from pathlib import Path

from .base import ArchEdge, check_cycles, check_layer_violations, node_metrics
from .go_parser import parse_go_file
from .java_parser import parse_java_file
from .js_parser import parse_js_file, parse_ts_file
from .python_parser import parse_python_file
from .registry import FuncParser, ParserRegistry

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


def _build_default_registry() -> ParserRegistry:
    """默认注册 5 语言解析器（SPI-1 配置点：新增语言在此 register 一行）。"""
    registry = ParserRegistry()
    registry.register(FuncParser(frozenset({".py"}), "python", parse_python_file))
    registry.register(FuncParser(frozenset({".java"}), "java", parse_java_file))
    registry.register(
        FuncParser(
            frozenset({".js", ".jsx", ".mjs", ".cjs"}), "javascript", parse_js_file
        )
    )
    registry.register(
        FuncParser(frozenset({".ts", ".tsx"}), "typescript", parse_ts_file)
    )
    registry.register(FuncParser(frozenset({".go"}), "go", parse_go_file))
    return registry


_DEFAULT_REGISTRY = _build_default_registry()


def _iter_source_files(code_dir: Path, registry: ParserRegistry):
    exts = registry.supported_extensions()
    for path in code_dir.rglob("*"):
        if path.is_dir():
            continue
        parts = set(path.parts)
        if parts & _SKIP_DIRS:
            continue
        if path.suffix.lower() in exts:
            yield path


def architecture_scan(code_dir: str, languages: list[str] | None = None) -> dict:
    """扫描项目架构，返回契约结构 dict（06 §5.5）。

    languages 非空时仅扫描指定语言的解析器（忽略大小写）；空/None 扫描全部。
    """
    root = Path(code_dir)
    if not root.is_dir():
        raise FileNotFoundError(f"codeDir not found: {code_dir}")

    active = _DEFAULT_REGISTRY.select_languages(languages)

    nodes: list = []
    all_calls: list[tuple[str, list[str]]] = []
    for path in _iter_source_files(root, _DEFAULT_REGISTRY):
        parser = _DEFAULT_REGISTRY.by_extension(path.suffix)
        if parser is None or parser not in active:
            continue
        rel = str(path.relative_to(root)).replace("\\", "/")
        try:
            source = path.read_bytes()
            file_nodes, calls = parser.parse(rel, source)
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
    violations += check_cycles(unique_edges)
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
