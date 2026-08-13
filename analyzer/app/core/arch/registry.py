"""SPI-1：语言解析器注册表（《04》§3.3/§11 G3 指标）。

目标：新增语言 = 实现 `BaseParser.parse` + 声明 `extensions`/`language` + `register`
一行，≤1 天（配置级），无需改动 `archscan` 的分派逻辑。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Callable

from .base import ArchNode

# 解析器统一返回契约：([节点], [(调用者 node_key, 候选调用名列表)])
ParseResult = tuple[list[ArchNode], list[tuple[str, list[str]]]]


class BaseParser(ABC):
    """语言解析器 SPI。

    子类需：
    - `extensions`：该语言文件后缀集合（小写，含点，如 {".py"}）
    - `language`：语言标识（小写，用于 `languages` 过滤，如 "python"）
    - 实现 `parse(file_path, source) -> (nodes, caller_calls)`
    """

    extensions: frozenset[str] = frozenset()
    language: str = ""

    @abstractmethod
    def parse(self, file_path: str, source: bytes) -> ParseResult:
        """返回 (nodes, caller_calls)；语义见 python_parser.parse_python_file。"""


class FuncParser(BaseParser):
    """函数式适配器：把既有 `parse_xxx_file` 函数包装为 BaseParser。

    用于零侵入接入存量解析器（go/java/js/ts/python），新语言可直接子类化 BaseParser。
    """

    def __init__(
        self,
        extensions: frozenset[str],
        language: str,
        fn: Callable[[str, bytes], ParseResult],
    ) -> None:
        self.extensions = frozenset(extensions)
        self.language = language
        self._fn = fn

    def parse(self, file_path: str, source: bytes) -> ParseResult:
        return self._fn(file_path, source)


class ParserRegistry:
    """后缀 → 解析器 的注册表；archscan 据此遍历文件并分派。"""

    def __init__(self) -> None:
        self._parsers: list[BaseParser] = []
        self._by_ext: dict[str, BaseParser] = {}

    def register(self, parser: BaseParser) -> None:
        self._parsers.append(parser)
        for ext in parser.extensions:
            self._by_ext[ext.lower()] = parser

    def by_extension(self, suffix: str) -> BaseParser | None:
        return self._by_ext.get(suffix.lower())

    def supported_extensions(self) -> frozenset[str]:
        """全部已注册后缀（小写），用于遍历候选源文件。"""
        return frozenset(self._by_ext)

    def select_languages(self, languages: list[str] | None) -> set[BaseParser]:
        """按 `language` 名过滤（忽略大小写）；空/None → 全部。

        未匹配任何已知语言名时返回空集（调用方据此产出空结果，而非忽略参数）。
        """
        if not languages:
            return set(self._parsers)
        wanted = {lang.lower() for lang in languages}
        return {p for p in self._parsers if p.language.lower() in wanted}
