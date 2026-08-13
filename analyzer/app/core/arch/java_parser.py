"""Java 架构提取（tree-sitter-java）。

基础版边界（v0.2）：节点 = 类（含内嵌类的简单名）；边 = 方法体内调用，
按调用名/对象段匹配类名（大小写不敏感）。类型解析与跨包依赖留待 v0.3。
"""

from __future__ import annotations

import threading

import tree_sitter_java
from tree_sitter import Language, Parser

from .base import ArchNode, infer_node_type

# 审查 H1：Parser 非线程安全 → 按线程缓存；Language 不可变可共享
_LANG = Language(tree_sitter_java.language())
_local = threading.local()


def _get_parser() -> Parser:
    parser = getattr(_local, "parser", None)
    if parser is None:
        parser = Parser(_LANG)
        _local.parser = parser
    return parser


def _text(node) -> str:
    return node.text.decode("utf-8", errors="replace")


def _invocation_names(invocation, field_types: dict[str, str]) -> list[str]:
    """method_invocation：object.method() → [目标类型?, 方法名]；method() → [方法名]。

    - object 为字段引用（service.getUser()）→ 用字段类型映射解析出类名；
    - object 为构造调用（new UserRepository()）→ 取其类型名；
    - 其余 object 直接取文本（如 this/变量名，按名匹配可能失效，属 v0.2 边界）。
    """
    obj = invocation.child_by_field_name("object")
    name = invocation.child_by_field_name("name")
    names: list[str] = []
    if obj is not None:
        if obj.type == "object_creation_expression":
            type_node = obj.child_by_field_name("type")
            names.append(_text(type_node) if type_node is not None else _text(obj))
        elif obj.type == "identifier" and _text(obj) in field_types:
            names.append(field_types[_text(obj)])
        elif obj.type == "field_access":
            names.append(_text(obj).split(".")[-1])
        else:
            names.append(_text(obj))
    if name is not None:
        names.append(_text(name))
    return names


def _collect_invocations(node, field_types: dict[str, str]) -> list[str]:
    out: list[str] = []

    def visit(n) -> None:
        if n.type == "method_invocation":
            out.extend(_invocation_names(n, field_types))
        for c in n.children:
            visit(c)

    visit(node)
    return out


def parse_java_file(
    file_path: str, source: bytes
) -> tuple[list[ArchNode], list[tuple[str, list[str]]]]:
    """返回 (nodes, caller_calls)；边匹配由 archscan 在全局节点集合上完成。"""
    tree = _get_parser().parse(source)
    root = tree.root_node
    file_stem = file_path.rsplit("/", 1)[-1]

    nodes: list[ArchNode] = []
    caller_calls: list[tuple[str, list[str]]] = []

    # 字段类型映射（Spring 注入风格）：field 名 → 类型名，用于解析 obj.method() 的目标
    field_types: dict[str, str] = {}

    def collect_fields(body) -> None:
        if body is None:
            return
        for c in body.children:
            if c.type == "field_declaration":
                declarator = c.child_by_field_name("declarator")
                type_node = c.child_by_field_name("type")
                if declarator is not None and type_node is not None:
                    field_types[_text(declarator)] = _text(type_node)

    def walk_class(node) -> None:
        name_node = node.child_by_field_name("name")
        if name_node is None:
            return
        name = _text(name_node)
        nodes.append(
            ArchNode(
                node_key=name,
                name=name,
                node_type=infer_node_type(name, file_stem),
                file_path=file_path,
                line=node.start_point[0] + 1,
            )
        )
        body = node.child_by_field_name("body")
        if body is not None:
            collect_fields(body)
            caller_calls.append((name, _collect_invocations(body, field_types)))
        # 内嵌类
        for c in body.children if body else []:
            if c.type == "class_declaration":
                walk_class(c)

    for child in root.children:
        if child.type == "class_declaration":
            walk_class(child)

    return nodes, caller_calls
