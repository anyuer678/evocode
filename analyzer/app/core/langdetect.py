"""语言识别（FR-2.1：扩展名映射 + 文件名规则，未知归 OTHER）。"""

EXTENSION_LANGUAGES = {
    ".java": "Java",
    ".py": "Python",
    ".ts": "TypeScript",
    ".tsx": "TypeScript",
    ".js": "JavaScript",
    ".jsx": "JavaScript",
    ".go": "Go",
    ".vue": "Vue",
    ".html": "HTML",
    ".htm": "HTML",
    ".css": "CSS",
    ".scss": "CSS",
    ".sql": "SQL",
    ".sh": "Shell",
    ".md": "Markdown",
    ".json": "JSON",
    ".xml": "XML",
    ".yml": "YAML",
    ".yaml": "YAML",
    ".kt": "Kotlin",
    ".c": "C",
    ".cpp": "C++",
    ".cs": "C#",
    ".rb": "Ruby",
    ".php": "PHP",
    ".rs": "Rust",
    ".swift": "Swift",
    ".scala": "Scala",
    ".dart": "Dart",
    ".lua": "Lua",
    ".r": "R",
    ".groovy": "Groovy",
    ".bat": "Shell",
    ".ps1": "PowerShell",
    ".gradle": "Groovy",
    ".toml": "TOML",
    ".ini": "INI",
}

FILENAME_LANGUAGES = {"dockerfile": "Dockerfile"}


def detect_language(rel_path: str) -> str:
    rel_path = rel_path.replace("\\", "/")
    name = rel_path.rsplit("/", 1)[-1]
    lower = name.lower()
    if lower in FILENAME_LANGUAGES:
        return FILENAME_LANGUAGES[lower]
    dot = lower.rfind(".")
    if dot > 0:
        return EXTENSION_LANGUAGES.get(lower[dot:], "OTHER")
    return "OTHER"
