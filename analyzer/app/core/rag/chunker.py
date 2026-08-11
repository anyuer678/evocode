"""RAG 切片（docs/02-开发指导.md §9.1，P6；AD-P6-3）。

单元 = tree-sitter 符号（函数/类/方法等顶层符号）；≤800 token/块；
超长符号按 1600 字符窗口滑切（overlap 200 ≈ 50 token）。
未支持语言 / 无符号文件 → 整文件切片兜底（模块级，symbol=None）。
"""

from __future__ import annotations

from dataclasses import dataclass

import tree_sitter_java
import tree_sitter_python
from tree_sitter import Language, Parser

# token 估算：无 tiktoken 依赖，按 ~4 chars/token 保守近似
_MAX_CHARS = 3200  # ≈800 token
_SLIDE_WINDOW = 1600  # ≈400 token
_SLIDE_OVERLAP = 200  # ≈50 token

_PARSERS: dict[str, Parser] = {
    "python": Parser(Language(tree_sitter_python.language())),
    "java": Parser(Language(tree_sitter_java.language())),
}

# 每种语言：符号节点类型 → 名称字段
_SYMBOL_TYPES: dict[str, dict[str, str]] = {
    "python": {
        "class_definition": "name",
        "function_definition": "name",
    },
    "java": {
        "class_declaration": "name",
        "interface_declaration": "name",
        "enum_declaration": "name",
        "record_declaration": "name",
        "method_declaration": "name",
        "constructor_declaration": "name",
    },
}

_SUPPORTED = frozenset(_PARSERS)


@dataclass
class CodeChunk:
    file_path: str
    language: str
    symbol: str | None
    chunk_index: int
    content: str
    start_line: int = 0
    end_line: int = 0


def normalize_language(language: str) -> str:
    """langdetect 返回 'Java'/'Python' → chunker 小写 key；不支持的归一为 'other'。"""
    lowered = language.lower()
    return lowered if lowered in _SUPPORTED else "other"


def supported_language(language: str) -> bool:
    return normalize_language(language) in _SUPPORTED


def _slide(text: str) -> list[str]:
    """超长文本按固定窗口滑切；返回的每片 ≤1600 chars（≈400 token）。"""
    if len(text) <= _MAX_CHARS:
        return [text]
    parts: list[str] = []
    start = 0
    n = len(text)
    while start < n:
        parts.append(text[start : start + _SLIDE_WINDOW])
        start += _SLIDE_WINDOW - _SLIDE_OVERLAP
    return parts


def _total_lines(source: str) -> int:
    return source.count("\n") + 1


def _text(node) -> str:
    return node.text.decode("utf-8", errors="replace")


def _symbol_chunks(
    file_path: str, language: str, source: str
) -> tuple[list[CodeChunk], list[tuple[int, int]]]:
    """按符号切片，返回 (chunks, 符号字节区间)。
    符号内部不再下沉（子符号并入父切片）。
    """
    parser = _PARSERS[language]
    tree = parser.parse(source.encode("utf-8"))
    root = tree.root_node
    symbol_fields = _SYMBOL_TYPES[language]
    chunks: list[CodeChunk] = []
    spans: list[tuple[int, int]] = []

    def walk(node, depth: int) -> None:
        if depth > 16:
            return
        if node.type in symbol_fields:
            name_node = node.child_by_field_name(symbol_fields[node.type])
            symbol = _text(name_node) if name_node is not None else node.type
            text = node.text.decode("utf-8", errors="replace")
            start_line = node.start_point[0] + 1
            end_line = node.end_point[0] + 1
            for part in _slide(text):
                chunks.append(
                    CodeChunk(
                        file_path,
                        language,
                        symbol,
                        len(chunks),
                        part,
                        start_line,
                        end_line,
                    )
                )
            spans.append((node.start_byte, node.end_byte))
            return  # 符号内部不再下沉
        for child in node.children:
            walk(child, depth + 1)

    walk(root, 0)
    return chunks, spans


def _module_fallback_chunks(
    file_path: str, language: str, source: str, spans: list[tuple[int, int]]
) -> list[CodeChunk]:
    """未被符号覆盖的区域（imports / 顶层语句）合并为模块级切片；无符号时整文件。"""
    if not spans:
        total = _total_lines(source)
        return [
            CodeChunk(file_path, language, None, i, part, 1, total)
            for i, part in enumerate(_slide(source))
        ]
    data = source.encode("utf-8")
    parts: list[str] = []
    cursor = 0
    for start, end in sorted(spans):
        if cursor < start:
            parts.append(data[cursor:start].decode("utf-8", errors="replace"))
        cursor = max(cursor, end)
    if cursor < len(data):
        parts.append(data[cursor:].decode("utf-8", errors="replace"))
    joined = "".join(p for p in parts if p.strip())
    if not joined.strip():
        return []
    total = _total_lines(source)
    return [
        CodeChunk(file_path, language, None, len(spans) + i, part, 1, total)
        for i, part in enumerate(_slide(joined))
    ]


def chunk_source(file_path: str, language: str, source: str) -> list[CodeChunk]:
    """按语言切片源码；language 值域对齐 langdetect（Java/Python/OTHER）。"""
    lang = normalize_language(language)
    if lang == "other":
        total = _total_lines(source)
        return [
            CodeChunk(file_path, language, None, i, part, 1, total)
            for i, part in enumerate(_slide(source))
        ]
    symbol_chunks, spans = _symbol_chunks(file_path, lang, source)
    symbol_chunks.extend(_module_fallback_chunks(file_path, lang, source, spans))
    # 全局重排 chunk_index（契约：file 内连续）
    for idx, chunk in enumerate(symbol_chunks):
        chunk.chunk_index = idx
    return symbol_chunks
