"""RAG 切片（docs/02-开发指导.md §9.1，P6；AD-P6-3）。

单元 = tree-sitter 符号（函数/类/方法等顶层符号）；≤800 token/块（估算）；
超长符号按 ≤400 token 窗口滑切（overlap 50 token，TD-10）。
未支持语言 / 无符号文件 → 整文件切片兜底（模块级，symbol=None）。
"""

from __future__ import annotations

from dataclasses import dataclass

import tree_sitter_java
import tree_sitter_python
from tree_sitter import Language, Parser

from ..tokenizer import estimate_tokens, split_at_token_budget

# token 预算（TD-10：估算驱动，替换 4 chars/token 近似——见 tokenizer.py）
_MAX_TOKENS = 800
_SLIDE_WINDOW_TOKENS = 400
_SLIDE_OVERLAP_TOKENS = 50

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
    """按 token 预算滑切：每片 ≤ _SLIDE_WINDOW_TOKENS token（估算），overlap 同预算。

    整段 ≤ _MAX_TOKENS（≈800）不切；超长按估算窗口滑切（TD-10，替代固定字符窗口）。
    """
    if estimate_tokens(text) <= _MAX_TOKENS:
        return [text]
    parts: list[str] = []
    n = len(text)
    start = 0
    while start < n:
        # 窗口大小：从 start 起 ≤ _SLIDE_WINDOW_TOKENS 的最大前缀
        window = split_at_token_budget(text[start:], _SLIDE_WINDOW_TOKENS)
        if window <= 0:
            # 单个字符即超预算（几乎不会出现，防死循环）
            window = 1
        end = start + window
        parts.append(text[start:end])
        # 前进 = 窗口 - overlap（以 token 估算近似字符步长）
        step = split_at_token_budget(text[end:], _SLIDE_OVERLAP_TOKENS)
        next_start = end - max(1, min(step, window - 1))
        if next_start <= start:
            next_start = start + 1
        start = next_start
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
