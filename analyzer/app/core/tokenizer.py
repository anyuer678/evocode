"""轻量 token 估算（docs/10-技术债管理方案.md TD-10）。

替换 4 chars/token 的粗糙近似：按词元类别估算，无第三方依赖
（不引 tiktoken——需下载 vocab 且离线不可用，违背 analyzer 依赖最小原则）。

估算规则（近似保守，宁多勿少——面向切片预算控制，非精确计费）：
- 英文/数字/下划线单词：ceil(len/4)（GPT 系平均 ~4 字符/token，与既有近似一致）
- 中文等 CJK 字符：每字符 1.5 token（cl100k 中文字符平均 ~1.3，取 1.5 保守）
- 连续标点/符号串：ceil(len/2)
- 空白串：1 token

仅用于切片窗口控制与文档说明；长数字串/空白等少数场景可能低估
（无 tiktoken 无法离线精确验证），对 ≤800 块上限的影响可接受。
"""

from __future__ import annotations

import math
import re

_WORD_RE = re.compile(r"[A-Za-z0-9_]+")
_CJK_RE = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]")
_PUNCT_RUN_RE = re.compile(r"[^\w\s\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]+")
_SPACE_RUN_RE = re.compile(r"\s+")


def estimate_tokens(text: str) -> int:
    """估算文本 token 数（保守偏高估）。空文本 → 0。"""
    if not text:
        return 0
    total = 0
    consumed = 0  # 已计入的字符数（单词/标点/空白均消耗原文）

    # 英文单词（含数字/下划线）
    for m in _WORD_RE.finditer(text):
        total += max(1, math.ceil(len(m.group(0)) / 4))
        consumed += len(m.group(0))
    # 中文等 CJK 字符（逐字符，每字 1.5）
    cjk_count = len(_CJK_RE.findall(text))
    total += math.ceil(cjk_count * 1.5)
    consumed += cjk_count
    # 标点/符号串
    for m in _PUNCT_RUN_RE.finditer(text):
        total += max(1, math.ceil(len(m.group(0)) / 2))
        consumed += len(m.group(0))
    # 空白串
    for m in _SPACE_RUN_RE.finditer(text):
        total += 1
        consumed += len(m.group(0))

    # 兜底：任何未覆盖字符（如 emoji）按 1 token/字符
    leftover = max(0, len(text) - consumed)
    total += leftover
    return total


def split_at_token_budget(text: str, max_tokens: int) -> int:
    """返回 ≤max_tokens 的最大前缀字符长度（按 estimate_tokens 单调累加）。

    估算单调不减，故可二分；返回 0 表示空文本/首个字符即超预算。
    注：二分内对 text[:mid] 全量扫描为 O(N log N)——正常源码无感；
    巨型 minified 符号（MB 级）才可感知，属可接受的工程折中（devlog TD-10）。
    """
    if not text or max_tokens <= 0:
        return 0
    n = len(text)
    lo, hi = 0, n
    # 二分找最大前缀使 estimate_tokens(text[:mid]) <= max_tokens
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if estimate_tokens(text[:mid]) <= max_tokens:
            lo = mid
        else:
            hi = mid - 1
    return lo
