"""JavaScript / TypeScript 架构提取（tree-sitter-javascript / tree-sitter-typescript）。

基础版边界（TD-09，对齐 java/python v0.2）：
- 节点 = 顶层 class / function / 常量导出（export const Foo）
- 边 = 调用名匹配（identifier/属性链根段匹配类名，忽略大小写/下划线）

TS 用 language_typescript（.ts/.tsx 走同一 parser，tree-sitter 对 JSX/TS 语法兼容）。
"""

from __future__ import annotations

import threading

import tree_sitter_javascript
import tree_sitter_typescript
from tree_sitter import Language, Parser

from .base import ArchNode, infer_node_type

# 审查 H1：Parser 非线程安全 → 按线程缓存；Language 不可变可共享
_LANG_JS = Language(tree_sitter_javascript.language())
_LANG_TS = Language(tree_sitter_typescript.language_typescript())
_local = threading.local()


def _get_parser_js() -> Parser:
    parser = getattr(_local, "parser_js", None)
    if parser is None:
        parser = Parser(_LANG_JS)
        _local.parser_js = parser
    return parser


def _get_parser_ts() -> Parser:
    parser = getattr(_local, "parser_ts", None)
    if parser is None:
        parser = Parser(_LANG_TS)
        _local.parser_ts = parser
    return parser


def _text(node) -> str:
    return node.text.decode("utf-8", errors="replace")


def _call_names(call_node) -> list[str]:
    """提取调用名：identifier → [名]；属性链 a.b.c() → [a, c]（根段匹配类名）。"""
    fn = call_node.child_by_field_name("function")
    if fn is None:
        return []
    if fn.type == "identifier":
        return [_text(fn)]
    if fn.type == "member_expression":
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


def _symbol_name(node) -> str | None:
    """顶层声明名：class/function/const/let/var 声明取 name 字段或声明符 id。"""
    name_node = node.child_by_field_name("name")
    if name_node is not None:
        return _text(name_node)
    # const Foo / let x：declarations 字段或子节点中首个 variable_declarator 的 id
    if node.type in ("lexical_declaration", "variable_declaration"):
        decs = node.child_by_field_name("declarations")
        if decs is not None:
            first = decs.named_children[0] if decs.named_children else None
        else:
            first = next(
                (c for c in node.named_children if c.type == "variable_declarator"),
                None,
            )
        if first is not None:
            id_node = first.child_by_field_name("name")
            if id_node is not None:
                return _text(id_node)
    return None


def _parse(
    file_path: str, source: bytes, parser: Parser
) -> tuple[list[ArchNode], list[tuple[str, list[str]]]]:
    tree = parser.parse(source)
    root = tree.root_node
    file_stem = file_path.rsplit("/", 1)[-1]

    nodes: list[ArchNode] = []
    caller_calls: list[tuple[str, list[str]]] = []

    for child in root.children:
        # export 包装：export class Foo / export function foo / export const Foo
        target = child
        if child.type == "export_statement":
            inner = child.named_children[0] if child.named_children else None
            if inner is None:
                continue
            target = inner
        if target.type in (
            "class_declaration",
            "function_declaration",
            "generator_function_declaration",
            "lexical_declaration",
            "variable_declaration",
        ):
            name = _symbol_name(target)
            if name is None:
                continue
            nodes.append(
                ArchNode(
                    node_key=name,
                    name=name,
                    node_type=infer_node_type(name, file_stem),
                    file_path=file_path,
                    line=target.start_point[0] + 1,
                )
            )
            caller_calls.append((name, _collect_calls(target)))

    return nodes, caller_calls


def parse_js_file(file_path: str, source: bytes):
    """JavaScript（.js/.jsx/.mjs/.cjs）。"""
    return _parse(file_path, source, _get_parser_js())


def parse_ts_file(file_path: str, source: bytes):
    """TypeScript（.ts/.tsx）。"""
    return _parse(file_path, source, _get_parser_ts())
