"""依赖清单解析（docs/06-API契约.md §5.10，P9d D1）。

纯文本/正则 + JSON 解析，不引第三方库：
- pom.xml：跨行正则提取 `<dependency>` 块内的 groupId/artifactId/version
  （只取直接依赖；dependencyManagement 不展开，parent 版本记 project 版本）
- package.json：`dependencies`/`devDependencies` 对象（JSON 解析）

返回统一结构 [{name, version, type, file, risk, reason, latest}]；
无 Maven/npm 依赖文件 → `{available: false, dependencies: []}`（契约 05.10）。
"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path

from .dep_eol_rules import EolRule, find_eol_rule

logger = logging.getLogger("evocode.analyzer.dependency")

_POM_DEPENDENCY_BLOCK_RE = re.compile(
    r"<dependency>\s*(.*?)\s*</dependency>", re.DOTALL
)
_POM_TAG_RE = re.compile(r"<(\w+)>\s*(.*?)\s*</\1>", re.DOTALL)


def scan_dependencies(code_dir: str) -> dict:
    """扫描目录依赖清单。

    Returns:
        {"available": bool, "dependencies": [{
            "name", "version", "type": "MAVEN"/"NPM", "file",
            "risk": "HIGH"/"LOW"/None, "reason": str|None, "latest": str|None,
            "isEol": bool}]}
    """
    code_path = Path(code_dir)
    if not code_path.is_dir():
        return {"available": False, "dependencies": []}

    deps: list[dict] = []
    # Maven：取根 pom.xml（多模块聚合时只扫根，子模块由 Maven 继承，避免重复）
    pom = code_path / "pom.xml"
    if pom.is_file():
        deps.extend(_parse_pom(pom))
    # npm：取根 package.json（排除 node_modules——扫描目录本就不含依赖安装产物）
    pkg = code_path / "package.json"
    if pkg.is_file():
        deps.extend(_parse_package_json(pkg))

    if not deps:
        return {"available": False, "dependencies": []}
    return {"available": True, "dependencies": deps}


def _parse_pom(pom: Path) -> list[dict]:
    try:
        content = pom.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        logger.warning("pom.xml 读取失败 %s：%s", pom, exc)
        return []

    # 先剥离 <dependencyManagement> 块：其内 <dependency> 仅声明版本管理，
    # 不是直接依赖（避免误抓 BOM 声明的构件）
    stripped = re.sub(
        r"<dependencyManagement>[\s\S]*?</dependencyManagement>",
        "",
        content,
    )
    # 再剥离 <parent> 块：parent 的 version 是父工程版本，不是本项目版本
    # （否则无 version 的直接依赖会错误继承父版本，导致 EOL 归因错误）
    stripped = re.sub(
        r"<parent>[\s\S]*?</parent>",
        "",
        stripped,
    )

    # project 版本（剥离 parent/dependencyManagement 后取首个根级 <version>）
    project_version = _pom_tag(stripped, "version")

    out: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for block in _POM_DEPENDENCY_BLOCK_RE.findall(stripped):
        tags = {m.group(1): m.group(2).strip() for m in _POM_TAG_RE.finditer(block)}
        group = tags.get("groupId")
        artifact = tags.get("artifactId")
        if not group or not artifact:
            continue
        name = f"{group}:{artifact}"
        if (name, "MAVEN") in seen:
            continue
        seen.add((name, "MAVEN"))
        version = tags.get("version") or project_version
        out.append(_build_item(name, version, "MAVEN", "pom.xml"))
    return out


def _pom_tag(content: str, tag: str) -> str | None:
    m = re.search(rf"<{tag}>\s*(.*?)\s*</{tag}>", content, re.DOTALL)
    if not m:
        return None
    # 排除 dependencyManagement 等嵌套：仅当该 tag 不在 <dependency> 块内时取
    # （简化：取第一个非 <parent>/<dependency> 上下文之外的 version）
    return m.group(1).strip()


def _parse_package_json(pkg: Path) -> list[dict]:
    try:
        data = json.loads(pkg.read_text(encoding="utf-8", errors="replace"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("package.json 解析失败 %s：%s", pkg, exc)
        return []
    if not isinstance(data, dict):
        return []

    out: list[dict] = []
    seen: set[str] = set()
    for section in ("dependencies", "devDependencies"):
        deps = data.get(section)
        if not isinstance(deps, dict):
            continue
        for name, raw_version in deps.items():
            if name in seen:
                continue
            seen.add(name)
            version = _npm_version(raw_version)
            out.append(_build_item(name, version, "NPM", "package.json"))
    return out


def _npm_version(raw: object) -> str | None:
    """npm 语义版本去符号：^2.5.14 / ~2.5.14 / >=2.0.0 → 取首个数字段。

    git URL 依赖（git+https://…/repo.git#v1.2.3）不提取——URL 不是版本，
    避免把 tag 误当版本。
    """
    if not isinstance(raw, str):
        return None
    s = raw.strip()
    if s.startswith(("git+", "github:", "file:", "http:", "https:")):
        return None
    m = re.search(r"\d+(?:\.\d+)*", s)
    return m.group(0) if m else None


def _build_item(name: str, version: str | None, dep_type: str, file: str) -> dict:
    rule: EolRule | None = find_eol_rule(_ecosystem(dep_type), name, version)
    if rule is not None:
        return {
            "name": name,
            "version": version,
            "type": dep_type,
            "file": file,
            "risk": rule.risk,
            "reason": rule.reason,
            "latest": rule.latest,
            "isEol": rule.risk == "HIGH",
        }
    return {
        "name": name,
        "version": version,
        "type": dep_type,
        "file": file,
        "risk": None,
        "reason": None,
        "latest": None,
        "isEol": False,
    }


def _ecosystem(dep_type: str) -> str:
    return {"MAVEN": "maven", "NPM": "npm"}.get(dep_type, dep_type.lower())
