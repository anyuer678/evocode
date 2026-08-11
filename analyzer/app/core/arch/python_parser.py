"""Python 架构提取（tree-sitter-python）。

基础版边界（v0.2）：节点 = 类 + 顶层函数；边 = 文件内调用匹配
（identifier 调用匹配类/函数名；attribute 调用按根段匹配类名，忽略大小写/下划线）。
方法级调用与跨文件全限定解析留待 v0.3。
"""

from __future__ import annotations

import tree_sitter_python
from tree_sitter import Language, Parser

from .base import ArchNode, infer_node_type

_LANG = Language(tree_sitter_python.language())
_PARSER = Parser(_LANG)


def _text(node) -> str:
    return node.text.decode("utf-8", errors="replace")


def _call_names(call_node) -> list[str]:
    """提取调用名：identifier → [名]；attribute → [根段, 叶子段]。

    例如 self.x.get → [self, get]（根段用于匹配类名，忽略大小写/下划线）。
    """
    fn = call_node.child_by_field_name("function")
    if fn is None:
        return []
    if fn.type == "identifier":
        return [_text(fn)]
    if fn.type == "attribute":
        parts = _text(fn).split(".")
        if len(parts) >= 2:
            return [parts[0], parts[-1]]
    return []


def parse_python_file(
    file_path: str, source: bytes
) -> tuple[list[ArchNode], list[tuple[str, list[str]]]]:
    """返回 (nodes, caller_calls)。

    caller_calls 为 (调用者 node_key, 候选调用名列表)；边匹配在全局节点集合上进行
    （archscan 聚合后统一处理，支持跨文件调用）。
    """
    tree = _PARSER.parse(source)
    root = tree.root_node

    nodes: list[ArchNode] = []
    caller_calls: list[tuple[str, list[str]]] = []
    file_stem = file_path.rsplit("/", 1)[-1]

    for child in root.children:
        if child.type == "class_definition" or child.type == "function_definition":
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

    return nodes, caller_calls


def _collect_calls(node) -> list[str]:
    """遍历子树收集所有 call 的候选名（去重保序）。"""
    out: list[str] = []

    def visit(n) -> None:
        if n.type == "call":
            out.extend(_call_names(n))
        for c in n.children:
            visit(c)

    visit(node)
    return out
