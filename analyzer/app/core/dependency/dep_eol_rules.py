"""依赖 EOL 判定规则表（docs/07-数据字典.md §3.4 / P9d D1）。

内置常量字典驱动：命中返回 risk 判定；未命中 → None（前端显示"未知版本，
建议人工确认"，不误报）。规则可扩展——新增一条 {key, match_fn, eol, latest, reason}。

判定逻辑：解析版本 → 主版本号 major（如 2.5.14 → 2）→ 按 (ecosystem, name, major)
匹配。risk_level 语义：HIGH=EOL；MEDIUM=接近 EOL/大版本过旧（本期规则暂只产 HIGH/LOW）。
"""

from __future__ import annotations

from dataclasses import dataclass

logger = None  # 占位（模块级无需日志）


@dataclass(frozen=True)
class EolRule:
    """一条 EOL 规则。

    - ecosystem: maven / npm / pip / go
    - name: 精确组件名（maven 用 groupId:artifactId，npm/pip 用包名）
    - version_prefix: 版本前缀匹配（解析后的完整版本字符串以该前缀开头），
      如 "2.5." 覆盖 2.5.x；"2." 覆盖 2.x
    - latest: 建议的最新版本（展示用）
    - reason: EOL 原因文案
    - risk: HIGH（EOL）/ MEDIUM（接近 EOL）
    """

    ecosystem: str
    name: str
    version_prefix: str
    latest: str
    reason: str
    risk: str = "HIGH"
    # name 前缀匹配（maven 生态：spring-boot 命中 spring-boot-starter-* 等子构件）
    prefix: bool = False


# 规则表（顺序优先：靠前者先匹配）
EOL_RULES: list[EolRule] = [
    # ---- Maven / Spring Boot ----
    EolRule("maven", "org.springframework.boot:spring-boot", "2.5.", "3.2+",
            "Spring Boot 2.5 已 EOL（OSS 支持 2023-11 结束，需升级 3.x "
            "并迁移 javax→jakarta）", prefix=True),
    EolRule("maven", "org.springframework.boot:spring-boot", "2.6.", "3.2+",
            "Spring Boot 2.6 已 EOL（OSS 支持 2022-11 结束）", prefix=True),
    EolRule("maven", "org.springframework.boot:spring-boot", "2.7.", "3.2+",
            "Spring Boot 2.7 已 EOL（OSS 支持 2023-11 结束）", prefix=True),
    EolRule("maven", "org.springframework:spring-core", "5.3.", "6.x",
            "Spring 5.3 已于 2026-12 结束 OSS 支持（Spring Framework 6.x 需 JDK17+）",
            prefix=True),
    # ---- npm ----
    EolRule("npm", "vue", "2.", "3.x",
            "Vue 2 已 EOL（2023-12-31 结束支持，官方建议升级 Vue 3）"),
    EolRule("npm", "react", "16.", "18+",
            "React 16 已 EOL（React 17/18 为当前支持线）"),
    EolRule("npm", "react", "17.", "18+",
            "React 17 已 EOL（2024-03 结束支持，建议升级 18/19）"),
    EolRule("npm", "node", "14.", "18+",
            "Node 14 已 EOL（2023-04 结束支持）"),
    EolRule("npm", "node", "16.", "18+",
            "Node 16 已 EOL（2023-09 结束支持）"),
    EolRule("npm", "node", "17.", "18+",
            "Node 17 已 EOL（2022-06 结束支持）"),
    # ---- pip ----
    EolRule("pip", "python", "3.7", "3.10+",
            "Python 3.7 已 EOL（2023-06 结束安全支持）"),
    EolRule("pip", "python", "3.8", "3.10+",
            "Python 3.8 已 EOL（2024-10 结束安全支持）"),
    EolRule("pip", "django", "2.", "4.x",
            "Django 2.x 已 EOL（2022-04 结束支持）"),
]


def find_eol_rule(ecosystem: str, name: str, version: str | None) -> EolRule | None:
    """按 (ecosystem, name, version 前缀) 查规则；无命中返回 None（不误报）。"""
    if not version:
        return None
    v = version.strip().lower()
    for rule in EOL_RULES:
        if rule.ecosystem != ecosystem:
            continue
        if rule.prefix:
            if not name.startswith(rule.name):
                continue
        elif rule.name != name:
            continue
        if v.startswith(rule.version_prefix):
            return rule
    return None


def version_major(version: str | None) -> str | None:
    """解析版本主号：2.5.14 → '2'；1.2.3-beta → '1'；无版本 → None。"""
    if not version:
        return None
    m = version.strip().lstrip("vV")
    parts = m.split(".")
    return parts[0] if parts and parts[0].isdigit() else None
