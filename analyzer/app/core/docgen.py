"""文档生成（P7b，02 附录 D.7）：README / ARCH / API 三类。

- README：项目简介/技术栈/目录结构/快速开始/运行要求
- ARCH：模块划分/分层/核心调用流程/部署方式（ASCII 图）
- API：从 controller 源码提取端点 → 生成表格文档
LLM 不可用/失败时按 docType 模板产出（TD-08：规则版降级，source=RULES）。
"""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any

from .llm import LLMClient

logger = logging.getLogger("evocode.analyzer.doc")

DOC_TYPES = ("README", "ARCH", "API")

_MAX_CONTROLLER_LINES = 4000  # API 输入截断保护
_MAX_SCAN_FILES = 200

_MAPPING_RE = re.compile(
    r"@(?:Get|Post|Put|Delete|Patch|Request)Mapping\(\s*(?:\"(?P<path>[^\"]*)\")?"
)
_SIGNATURE_RE = re.compile(
    r"^\s*(?:public|protected|private)\s+[\w<>\[\],\s]+\s+(?P<name>\w+)\s*\("
)

README_SYSTEM = (
    "你是技术文档专家。基于项目分析结果生成 README（markdown），"
    "包含：项目简介、技术栈、目录结构、快速开始、运行要求。"
    "只输出 markdown，不加围栏。输出 JSON "
    "{ \"title\": \"...\", \"content\": \"markdown字符串\" }。"
)

ARCH_SYSTEM = (
    "你是架构文档专家。基于架构分析结果生成架构说明文档（markdown）："
    "模块划分、分层说明、核心调用流程、部署方式（含 ASCII 图）。"
    "只输出 markdown，不加围栏。输出 JSON "
    "{ \"title\": \"...\", \"content\": \"markdown字符串\" }。"
)

API_SYSTEM = (
    "你是 API 文档专家。基于控制器/路由解析结果生成 API 文档（markdown）："
    "每个端点的方法、路径、入参、出参、用途说明（表格形式）。"
    "只输出 markdown，不加围栏。输出 JSON "
    "{ \"title\": \"...\", \"content\": \"markdown字符串\" }。"
)


def generate_doc(
    llm: LLMClient,
    doc_type: str,
    *,
    scan: dict[str, Any] | None,
    arch: dict[str, Any] | None,
    project_info: dict[str, Any],
    code_dir: str | None,
) -> dict[str, Any]:
    """生成三类文档之一；返回 {docType, title, content, source}。

    TD-08：LLM 未配置或调用失败 → 规则版模板降级（source=RULES），
    保证无 Key 全链路可演示；LLM 成功 → source=LLM。
    """
    dt = doc_type.upper()
    if dt not in DOC_TYPES:
        raise ValueError(f"不支持的文档类型：{doc_type}")
    if not llm.available():
        logger.info("doc %s 无 LLM，规则版降级", dt)
        return _rules_doc(dt, scan, arch, project_info, code_dir)
    if dt == "README":
        system, user = README_SYSTEM, _readme_user(scan, project_info)
    elif dt == "ARCH":
        system, user = ARCH_SYSTEM, _arch_user(arch)
    else:
        system, user = API_SYSTEM, _api_user(code_dir)
    try:
        data = llm.chat_json(system, user)
        return {
            "docType": dt,
            "title": str(data.get("title") or f"{dt} 文档"),
            "content": str(data.get("content") or ""),
            "source": "LLM",
        }
    except Exception as exc:
        logger.warning("doc %s LLM 失败，规则版降级：%s", dt, exc)
        return _rules_doc(dt, scan, arch, project_info, code_dir)


def _rules_doc(
    dt: str,
    scan: dict[str, Any] | None,
    arch: dict[str, Any] | None,
    info: dict[str, Any],
    code_dir: str | None,
) -> dict[str, Any]:
    """TD-08 规则版模板：基于已落库/扫描的结构化数据生成 Markdown。"""
    if dt == "README":
        title, content = _readme_rules(scan, info)
    elif dt == "ARCH":
        title, content = _arch_rules(arch)
    else:
        title, content = _api_rules(code_dir)
    return {"docType": dt, "title": title, "content": content, "source": "RULES"}


def _readme_rules(scan: dict[str, Any] | None, info: dict[str, Any]) -> tuple[str, str]:
    name = info.get("name") or "未知项目"
    desc = info.get("description") or "（无描述）"
    langs = (scan or {}).get("languages") or {}
    frameworks = (scan or {}).get("frameworks") or []
    loc_total = (scan or {}).get("locTotal") or 0
    file_count = (scan or {}).get("fileCount") or 0
    lang_line = "、".join(f"{k} {v}%" for k, v in langs.items()) or "未知"
    stack = "、".join(map(str, frameworks)) or "（未识别）"
    files = (scan or {}).get("files") or []
    tree = "\n".join(
        f"- `{f.get('path', '?')}`（{f.get('language', '?')}，{f.get('loc', 0)} 行）"
        for f in files[:30]
    ) or "- （无文件清单）"
    content = (
        f"# {name}\n\n"
        f"> 本文档由 EvoCode 规则引擎生成（未启用 LLM 精修）。\n\n"
        f"## 项目简介\n\n{desc}\n\n"
        f"## 技术栈\n\n- 语言构成：{lang_line}\n"
        f"- 框架：{stack}\n"
        f"- 规模：{loc_total} 行 / {file_count} 文件\n\n"
        f"## 目录结构（前 {min(len(files), 30)} 项）\n\n{tree}\n\n"
        f"## 快速开始\n\n"
        f"```bash\n# 构建与运行命令因项目而异，请参考各模块文档\n```\n\n"
        f"## 运行要求\n\n- 环境：请参考各模块说明\n"
    )
    return f"{name} 使用说明", content


def _arch_rules(arch: dict[str, Any] | None) -> tuple[str, str]:
    nodes = (arch or {}).get("nodes") or []
    edges = (arch or {}).get("edges") or []
    violations = (arch or {}).get("violations") or []
    by_type: dict[str, list[str]] = {}
    for n in nodes:
        kind = n.get("type") if isinstance(n, dict) else "未知"
        by_type.setdefault(str(kind), []).append(
            f"- `{n.get('name', '?')}`：{n.get('description', '')}"
            if isinstance(n, dict)
            else f"- {n}"
        )
    module_lines = []
    for kind, lines in by_type.items():
        module_lines.append(f"### {kind}\n\n" + "\n".join(lines[:30]))
    module_block = "\n\n".join(module_lines) or "- （无节点数据）"
    edge_lines = "\n".join(
        f"- {e.get('source', '?')} → {e.get('target', '?')}（{e.get('type', '')}）"
        for e in edges[:50] if isinstance(e, dict)
    ) or "- （无调用关系）"
    viol_lines = "\n".join(
        f"- `{v.get('description', '')}`（{v.get('severity', '')}）"
        for v in violations[:20] if isinstance(v, dict)
    ) or "- 无违规"
    content = (
        f"# 架构说明\n\n"
        f"> 本文档由 EvoCode 规则引擎生成（未启用 LLM 精修）。\n\n"
        f"## 模块划分\n\n{module_block}\n\n"
        f"## 核心调用流程\n\n{edge_lines}\n\n"
        f"## 架构违规\n\n{viol_lines}\n\n"
        f"## 部署方式\n\n- 请参考各模块部署说明\n"
    )
    return "架构说明", content


def _api_rules(code_dir: str | None) -> tuple[str, str]:
    if not code_dir or not os.path.isdir(code_dir):
        content = (
            "# API 文档\n\n"
            "> 未提供代码目录，无法解析控制器端点。请先发起一次分析。\n"
        )
        return "API 文档", content
    endpoints = _extract_controllers(code_dir)
    if not endpoints:
        content = (
            "# API 文档\n\n"
            "> 代码目录中未发现 REST 控制器"
            "（Spring @*Mapping / FastAPI 路由未解析到）。\n"
        )
        return "API 文档", content
    lines = [
        "# API 文档",
        "",
        "> 本文档由 EvoCode 规则引擎生成（未启用 LLM 精修）。",
        "",
        "| 方法 | 路径 | 处理函数 |",
        "|---|---|---|",
    ]
    for ep in endpoints:
        m = re.match(r"^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s+(\S+)\s+(.+)$", ep)
        if m:
            lines.append(f"| {m.group(1)} | `{m.group(2)}` | {m.group(3)} |")
        else:
            lines.append(f"| - | {ep} | - |")
    lines.extend(["", "## 备注", "", "- 参数/响应结构请结合具体实现查看。"])
    return "API 文档", "\n".join(lines)


def _readme_user(scan: dict[str, Any] | None, info: dict[str, Any]) -> str:
    name = info.get("name") or "未知项目"
    desc = info.get("description") or "（无描述）"
    scan_block = "（无扫描数据）"
    if scan:
        slim = {
            "languages": scan.get("languages"),
            "locTotal": scan.get("locTotal"),
            "fileCount": scan.get("fileCount"),
            "frameworks": scan.get("frameworks"),
            "hasBackend": scan.get("hasBackend"),
            "hasFrontend": scan.get("hasFrontend"),
            "dbHint": scan.get("dbHint"),
            "files": (scan.get("files") or [])[:_MAX_SCAN_FILES],
        }
        scan_block = json.dumps(slim, ensure_ascii=False)
    return (
        f"项目名称：{name}\n项目简介：{desc}\n\n"
        f"扫描摘要：\n{scan_block}\n\n请生成 README 文档。"
    )


def _arch_user(arch: dict[str, Any] | None) -> str:
    if not arch:
        return "（无架构分析数据）\n\n请基于现有信息说明：架构文档需标注数据缺失。"
    slim = {
        "nodes": (arch.get("nodes") or [])[:100],
        "edges": (arch.get("edges") or [])[:200],
        "violations": (arch.get("violations") or [])[:30],
    }
    return "架构分析结果：\n" + json.dumps(slim, ensure_ascii=False)


def _api_user(code_dir: str | None) -> str:
    if not code_dir or not os.path.isdir(code_dir):
        return "（未提供代码目录）\n\n请基于现有信息说明：API 文档需在项目分析后生成。"
    endpoints = _extract_controllers(code_dir)
    if not endpoints:
        return "（代码目录中未发现 REST 控制器）\n\n请基于现有信息说明。"
    return "控制器端点解析结果：\n" + "\n".join(endpoints)


def _extract_controllers(code_dir: str) -> list[str]:
    """扫描 java 文件的 REST 映射注解与方法签名，返回可读端点行。"""
    lines_out: list[str] = []
    for root, _dirs, files in os.walk(code_dir):
        for name in files:
            if not name.endswith(".java"):
                continue
            if len(lines_out) >= _MAX_CONTROLLER_LINES:
                return lines_out
            path = os.path.join(root, name)
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            if "@RestController" not in text and "@Controller" not in text:
                continue
            rel = os.path.relpath(path, code_dir).replace("\\", "/")
            lines_out.append(f"# {rel}")
            in_controller = False
            class_path = ""  # 每文件重置，防跨文件泄漏（审查 M5）
            for raw in text.splitlines():
                line = raw.strip()
                if "@RestController" in line or "@Controller" in line:
                    in_controller = True
                    continue
                if not in_controller:
                    continue
                m = _MAPPING_RE.search(line)
                if m:
                    method = _method_of(line)
                    path = m.group("path") or ""
                    if "@RequestMapping" in line and method == "REQUEST":
                        class_path = path  # 类级前缀
                        continue
                    endpoint = f"{method} {class_path}{path}"
                    lines_out.append(endpoint)
                    continue
                s = _SIGNATURE_RE.match(line)
                if s and lines_out and not lines_out[-1].startswith("#"):
                    lines_out[-1] += f"  →  {s.group('name')}()"
            if len(lines_out) >= _MAX_CONTROLLER_LINES:
                break
    return lines_out


def _method_of(line: str) -> str:
    for marker, method in (
        ("@GetMapping", "GET"),
        ("@PostMapping", "POST"),
        ("@PutMapping", "PUT"),
        ("@DeleteMapping", "DELETE"),
        ("@PatchMapping", "PATCH"),
    ):
        if marker in line:
            return method
    return "REQUEST"
