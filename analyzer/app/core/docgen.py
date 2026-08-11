"""文档生成（P7b，02 附录 D.7）：README / ARCH / API 三类。

- README：项目简介/技术栈/目录结构/快速开始/运行要求
- ARCH：模块划分/分层/核心调用流程/部署方式（ASCII 图）
- API：从 controller 源码提取端点 → LLM 生成表格文档
LLM 失败抛异常（文档无法规则降级，由路由映射错误语义）。
"""

from __future__ import annotations

import json
import os
import re
from typing import Any

from .llm import LLMClient

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
    """生成三类文档之一；返回 {docType, title, content}。"""
    dt = doc_type.upper()
    if dt not in DOC_TYPES:
        raise ValueError(f"不支持的文档类型：{doc_type}")
    if dt == "README":
        system, user = README_SYSTEM, _readme_user(scan, project_info)
    elif dt == "ARCH":
        system, user = ARCH_SYSTEM, _arch_user(arch)
    else:
        system, user = API_SYSTEM, _api_user(code_dir)
    data = llm.chat_json(system, user)
    return {
        "docType": dt,
        "title": str(data.get("title") or f"{dt} 文档"),
        "content": str(data.get("content") or ""),
    }


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
            class_path = ""
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
