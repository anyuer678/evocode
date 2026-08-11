"""技术栈识别（FR-2.4：构建文件 + 依赖关键字 → 框架 / 前后端 / 数据库提示）。"""

import json
from dataclasses import dataclass, field
from pathlib import Path

FRAMEWORK_KEYWORDS = {
    "vue": "Vue",
    "react": "React",
    "@angular/core": "Angular",
    "svelte": "Svelte",
    "electron": "Electron",
    "next": "Next.js",
    "nuxt": "Nuxt",
    "three": "Three.js",
    "express": "Express",
    "koa": "Koa",
    "nest": "NestJS",
    "fastapi": "FastAPI",
    "uvicorn": "FastAPI",
    "flask": "Flask",
    "django": "Django",
    "tornado": "Tornado",
    "spring-boot": "Spring Boot",
    "mybatis": "MyBatis",
    "hibernate": "Hibernate",
    "lombok": "Lombok",
    "fastjson": "Fastjson",
    "mybatis-plus": "MyBatis-Plus",
    "axios": "Axios",
    "pinia": "Pinia",
    "vite": "Vite",
    "webpack": "Webpack",
    "typescript": "TypeScript",
    "jquery": "jQuery",
    "element-plus": "Element Plus",
    "ant-design-vue": "Ant Design Vue",
    "antd": "Ant Design",
}

DB_KEYWORDS = {
    "sqlite": "SQLite",
    "mysql": "MySQL",
    "mysql2": "MySQL",
    "mysql-connector-j": "MySQL",
    "pg": "PostgreSQL",
    "pgvector": "PostgreSQL",
    "psycopg2": "PostgreSQL",
    "postgresql": "PostgreSQL",
    "redis": "Redis",
    "mongodb": "MongoDB",
    "mssql": "SQL Server",
    "sqlserver": "SQL Server",
    "h2": "H2",
    "sqlalchemy": "SQLAlchemy",
    "mybatis-plus": "MyBatis-Plus",
}

BACKEND_MARKER_FILES = {
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "requirements.txt",
    "pyproject.toml",
    "go.mod",
    "Cargo.toml",
    "composer.json",
    "setup.py",
}

FRONTEND_MARKER_FILES = {"index.html", "vite.config.js", "vite.config.ts"}


@dataclass
class StackInfo:
    frameworks: list[str] = field(default_factory=list)
    has_backend: bool = False
    has_frontend: bool = False
    db_hint: list[str] = field(default_factory=list)


def _match_keywords(text: str, table: dict) -> list[str]:
    found: list[str] = []
    for key, label in table.items():
        if key in text and label not in found:
            found.append(label)
    return found


def detect_stack(code_dir: Path) -> StackInfo:
    stack = StackInfo()
    root_files = {p.name for p in code_dir.iterdir() if p.is_file()}
    has_package_json = "package.json" in root_files

    if any(m in root_files for m in BACKEND_MARKER_FILES):
        stack.has_backend = True
    if any(m in root_files for m in FRONTEND_MARKER_FILES):
        stack.has_frontend = True

    if "pom.xml" in root_files:
        stack.frameworks.append("Maven")
        text = (code_dir / "pom.xml").read_text(encoding="utf-8", errors="ignore")
        for label in _match_keywords(text, FRAMEWORK_KEYWORDS):
            stack.frameworks.append(label)
        for label in _match_keywords(text, DB_KEYWORDS):
            stack.db_hint.append(label)
        if "spring-boot" in text:
            stack.has_backend = True

    if has_package_json:
        try:
            data = json.loads(
                (code_dir / "package.json").read_text(encoding="utf-8", errors="ignore")
            )
        except (json.JSONDecodeError, OSError):
            data = {}
        deps = {
            **(data.get("dependencies") or {}),
            **(data.get("devDependencies") or {}),
        }
        for name in deps:
            base = name.split("/")[-1].lower()
            for label in _match_keywords(name, FRAMEWORK_KEYWORDS):
                if label not in stack.frameworks:
                    stack.frameworks.append(label)
            if name in ("express", "koa", "nest", "@nestjs/core") or base in (
                "express",
                "koa",
            ):
                stack.has_backend = True
            elif base in ("vue", "react", "svelte", "jquery"):
                stack.has_frontend = True
        for label in _match_keywords(json.dumps(deps), DB_KEYWORDS):
            stack.db_hint.append(label)

    for marker in FRONTEND_MARKER_FILES:
        if marker in root_files and marker == "index.html":
            stack.has_frontend = True

    if "requirements.txt" in root_files:
        text = (code_dir / "requirements.txt").read_text(
            encoding="utf-8", errors="ignore"
        )
        for label in _match_keywords(text.lower(), FRAMEWORK_KEYWORDS):
            stack.frameworks.append(label)
        for label in _match_keywords(text.lower(), DB_KEYWORDS):
            stack.db_hint.append(label)

    stack.db_hint = list(dict.fromkeys(stack.db_hint))
    return stack
