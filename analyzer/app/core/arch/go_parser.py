"""Go 架构提取（tree-sitter-go）。

基础版边界（TD-09，对齐其他语言 v0.2）：
- 节点 = 顶层 function_declaration（含 method_declaration 的接收者类型名）
- 边 = 调用名匹配（identifier/selector 的根段匹配类型名，忽略大小写/下划线）

Go 约定：method_declaration 的接收者（receiver）是类型名 → 节点 key 用类型名，
使跨文件 method 调用（svc.Get() → Service 类型）可按类型聚合。
"""

from __future__ import annotations

import threading

import tree_sitter_go
from tree_sitter import Language, Parser

from .base import ArchNode, infer_node_type

# 审查 H1：Parser 非线程安全 → 按线程缓存；Language 不可变可共享
_LANG = Language(tree_sitter_go.language())
_local = threading.local()


def _get_parser() -> Parser:
    parser = getattr(_local, "parser", None)
    if parser is None:
        parser = Parser(_LANG)
        _local.parser = parser
    return parser


def _text(node) -> str:
    return node.text.decode("utf-8", errors="replace")


def _call_names(call_node) -> list[str]:
    """提取调用名：identifier → [名]；selector a.b() → [a, b]（根段匹配类型名）。"""
    fn = call_node.child_by_field_name("function")
    if fn is None:
        return []
    if fn.type == "identifier":
        return [_text(fn)]
    if fn.type == "selector_expression":
        parts = _text(fn).split(".")
        if len(parts) >= 2:
            return [parts[0], parts[-1]]
    return []


def _collect_calls(node) -> list[str]:
    out: list[str] = []

    def visit(n) -> None:
        if n.type == "call_expression":
            out.extend(_call_names(n))
        for c in n.children:
            visit(c)

    visit(node)
    return out


def _receiver_type(node) -> str | None:
    """method_declaration 接收者类型：func (s *Service) Get() → Service。"""
    receiver = node.child_by_field_name("receiver")
    if receiver is None:
        return None
    text = _text(receiver)
    # (s *Service) / (s Service) → 去括号与指针后取末段
    cleaned = text.strip("()").replace("*", "").strip()
    parts = cleaned.split()
    return parts[-1] if parts else None


def parse_go_file(
    file_path: str, source: bytes
) -> tuple[list[ArchNode], list[tuple[str, list[str]]]]:
    tree = _get_parser().parse(source)
    root = tree.root_node
    file_stem = file_path.rsplit("/", 1)[-1]

    nodes: list[ArchNode] = []
    caller_calls: list[tuple[str, list[str]]] = []
    seen: set[str] = set()

    for child in root.children:
        if child.type == "function_declaration":
            name_node = child.child_by_field_name("name")
            if name_node is None:
                continue
            name = _text(name_node)
            nodes.append(
                ArchNode(
                    node_key=name,
                    name=name,
                    node_type=infer_node_type(name, file_stem),
                    file_path=file_path,
                    line=child.start_point[0] + 1,
                )
            )
            caller_calls.append((name, _collect_calls(child)))
        elif child.type == "method_declaration":
            recv = _receiver_type(child)
            if recv is None:
                continue
            # 接收者类型作为节点（聚合该类型全部方法调用）；同类型去重
            if recv not in seen:
                seen.add(recv)
                nodes.append(
                    ArchNode(
                        node_key=recv,
                        name=recv,
                        node_type=infer_node_type(recv, file_stem),
                        file_path=file_path,
                        line=child.start_point[0] + 1,
                    )
                )
            caller_calls.append((recv, _collect_calls(child)))

    return nodes, caller_calls
