"""忽略规则引擎（FR-2.2：默认规则 + .evocodeignore 自定义，! 取反最后匹配生效）。"""

from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_IGNORED_DIRS = {
    "node_modules",
    ".git",
    "dist",
    "target",
    "build",
    "__pycache__",
    "venv",
    ".venv",
    ".idea",
    ".vscode",
    ".next",
    "out",
    "coverage",
    ".gradle",
}

DEFAULT_IGNORED_FILE_SUFFIXES = {
    ".lock",
    ".min.js",
    ".map",
    ".pyc",
    ".pyo",
    ".class",
    ".jar",
}
DEFAULT_IGNORED_FILES = {"package-lock.json", "yarn.lock", "pnpm-lock.yaml"}

HIDDEN_WHITELIST = {
    ".github",
    ".gitignore",
    ".dockerignore",
    ".gitattributes",
    ".editorconfig",
    ".npmrc",
    ".env.example",
    ".evocodeignore",
    ".prettierrc",
    ".prettierrc.json",
    ".prettierrc.cjs",
    ".eslintrc",
    ".eslintrc.json",
    ".eslintrc.cjs",
    ".flake8",
    ".pylintrc",
    ".babelrc",
    ".browserslistrc",
    ".nvmrc",
}


@dataclass
class IgnoreRule:
    """kind：dir=目录名（命中其下所有文件）/ suffix=文件后缀 / file=精确相对路径。"""

    kind: str
    value: str
    negate: bool = False

    def matches(self, rel_path: str, is_dir: bool) -> bool:
        rel_path = rel_path.replace("\\", "/")
        if self.kind == "dir":
            return self.value in rel_path.split("/")
        if self.kind == "suffix":
            return not is_dir and rel_path.endswith(self.value)
        return not is_dir and rel_path == self.value


@dataclass
class IgnoreRules:
    rules: list[IgnoreRule] = field(default_factory=list)

    def is_ignored(self, rel_path: str, is_dir: bool) -> bool:
        rel_path = rel_path.replace("\\", "/")
        first = rel_path.split("/")[0]
        if is_dir:
            if first in DEFAULT_IGNORED_DIRS:
                return True
            if first.startswith(".") and first not in HIDDEN_WHITELIST:
                return True
        else:
            name = rel_path.rsplit("/", 1)[-1]
            if name in DEFAULT_IGNORED_FILES:
                return True
            if any(name.endswith(s) for s in DEFAULT_IGNORED_FILE_SUFFIXES):
                return True
            if name.startswith(".") and name not in HIDDEN_WHITELIST:
                return True
        ignored = False
        for rule in self.rules:
            if rule.matches(rel_path, is_dir):
                ignored = not rule.negate
        return ignored

    def may_be_reincluded(self, dir_rel: str) -> bool:
        """目录下是否存在 ! 取反规则（有则不得剪枝）。"""
        prefix = dir_rel.replace("\\", "/")
        return any(
            rule.negate and rule.value.replace("\\", "/").startswith(prefix)
            for rule in self.rules
        )


def parse_evocodeignore(code_dir: Path) -> IgnoreRules:
    """读取项目根 .evocodeignore：逐行规则，# 注释，! 取反。"""
    rules = IgnoreRules()
    file = code_dir / ".evocodeignore"
    if not file.exists():
        return rules
    try:
        lines = file.read_text(encoding="utf-8").splitlines()
    except (UnicodeDecodeError, OSError):
        return rules
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        negate = line.startswith("!")
        if negate:
            line = line[1:].strip()
        line = line.rstrip("/")
        if not line:
            continue
        if "/" in line:
            # 含路径分隔符：文件精确规则（若以 / 结尾则是目录规则）
            rules.rules.append(IgnoreRule("file", line.lstrip("/"), negate))
        else:
            rules.rules.append(IgnoreRule("dir", line, negate))
    return rules
