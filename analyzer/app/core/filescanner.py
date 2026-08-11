"""项目扫描管线（FR-2：walk → 忽略规则 → 二进制/大文件过滤 → 语言/LOC 统计）。"""

import os
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from .ignore import IgnoreRules, parse_evocodeignore
from .langdetect import detect_language
from .loc import count_loc
from .stackdetect import detect_stack

MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_FILES = 50000
BINARY_SAMPLE_BYTES = 1024


@dataclass
class ScanFile:
    path: str
    language: str
    loc: int
    sizeBytes: int


@dataclass
class ScanResult:
    languages: dict[str, float] = field(default_factory=dict)
    loc_total: int = 0
    file_count: int = 0
    ignored_count: int = 0
    frameworks: list[str] = field(default_factory=list)
    has_backend: bool = False
    has_frontend: bool = False
    db_hint: list[str] = field(default_factory=list)
    files: list[ScanFile] = field(default_factory=list)
    skipped_big_files: int = 0
    truncated: bool = False


def is_binary(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            head = f.read(BINARY_SAMPLE_BYTES)
    except OSError:
        return True
    return b"\x00" in head


def scan_project(code_dir: Path) -> ScanResult:
    rules: IgnoreRules = parse_evocodeignore(code_dir)
    result = ScanResult()
    per_lang_loc: dict[str, int] = defaultdict(int)

    for root, dirs, names in os.walk(code_dir):
        root_path = Path(root)
        root_rel = root_path.relative_to(code_dir)
        keep_dirs = []
        for d in sorted(dirs):
            d_rel = d if str(root_rel) == "." else (root_rel / d).as_posix()
            if rules.is_ignored(d_rel, is_dir=True):
                if rules.may_be_reincluded(d_rel):
                    keep_dirs.append(d)
                else:
                    result.ignored_count += 1
            else:
                keep_dirs.append(d)
        dirs[:] = keep_dirs

        for name in sorted(names):
            file_path = root_path / name
            rel = name if str(root_rel) == "." else (root_rel / name).as_posix()
            if rules.is_ignored(rel, is_dir=False):
                result.ignored_count += 1
                continue
            try:
                size = file_path.stat().st_size
            except OSError:
                result.ignored_count += 1
                continue
            if size > MAX_FILE_BYTES:
                result.skipped_big_files += 1
                continue
            if is_binary(file_path):
                result.ignored_count += 1
                continue
            try:
                text = file_path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                result.ignored_count += 1
                continue
            language = detect_language(rel)
            loc = count_loc(text)
            per_lang_loc[language] += loc
            result.loc_total += loc
            result.file_count += 1
            result.files.append(
                ScanFile(path=rel, language=language, loc=loc, sizeBytes=size)
            )
            if result.file_count >= MAX_FILES:
                result.truncated = True
                break
        if result.truncated:
            break

    if result.loc_total > 0:
        result.languages = {
            lang: round(loc * 100 / result.loc_total, 1)
            for lang, loc in sorted(per_lang_loc.items(), key=lambda kv: -kv[1])
        }

    stack = detect_stack(code_dir)
    result.frameworks = stack.frameworks
    result.has_backend = stack.has_backend
    result.has_frontend = stack.has_frontend
    result.db_hint = stack.db_hint
    return result
