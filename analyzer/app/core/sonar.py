"""SonarQube 客户端（AD-8：docker 社区版 + sonar-scanner；P3）。

职责：可用性探测 → sonar-scanner 执行 → CE 任务轮询 → 指标/issues 拉取与归一。
任一环节失败/未配置均返回不可用（available=false），不影响其他分析维度（06 §5.3）。
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import time
from typing import Any

import httpx

from app.core.rule_advice import advice_for

logger = logging.getLogger("evocode.analyzer.sonar")

_METRIC_KEYS = (
    "bugs,vulnerabilities,code_smells,duplicated_lines_density,coverage,complexity"
)
_ISSUE_TYPES = "BUG,VULNERABILITY,CODE_SMELL"

# 扫描排除：构建产物/依赖/版本控制目录（与扫描器 ignore 规则对齐）
_EXCLUSIONS = (
    "**/node_modules/**,**/.git/**,**/target/**,**/dist/**,**/build/**,"
    "**/.venv/**,**/__pycache__/**,**/coverage/**,**/*.min.js,"
    "**/package-lock.json,**/yarn.lock,**/poetry.lock"
)


class SonarClient:
    """Sonar 质量扫描客户端。"""

    def __init__(
        self,
        host_url: str,
        token: str,
        scanner: str = "",
        timeout_seconds: float = 30.0,
        ce_poll_seconds: float = 3.0,
        ce_poll_max: int = 40,
    ) -> None:
        self._host = host_url.rstrip("/")
        self._token = token
        self._scanner = scanner or self._find_scanner()
        self._timeout = timeout_seconds
        self._ce_poll_seconds = ce_poll_seconds
        self._ce_poll_max = max(1, ce_poll_max)

    @staticmethod
    def _find_scanner() -> str:
        for name in ("sonar-scanner", "sonar-scanner.bat"):
            found = shutil.which(name)
            if found:
                return found
        return ""

    # ---- 可用性 ----
    def available(self) -> bool:
        """token 已配置、host 可达、scanner 可执行，三者缺一即不可用。"""
        if not self._token.strip():
            logger.info("SONAR_TOKEN 未配置，质量分析降级 N/A")
            return False
        if not self._scanner:
            logger.info("未找到 sonar-scanner，质量分析降级 N/A")
            return False
        try:
            with httpx.Client(timeout=self._timeout) as client:
                resp = client.get(
                    f"{self._host}/api/system/status",
                    headers=self._auth(),
                )
                resp.raise_for_status()
                return resp.json().get("status") == "UP"
        except Exception as exc:
            logger.info("Sonar 不可达，质量分析降级 N/A：%s", exc)
            return False

    # ---- 主流程 ----
    def scan(
        self, project_id: int, code_dir: str
    ) -> tuple[dict[str, Any], list[dict[str, Any]]] | None:
        """执行扫描并返回 (metrics, issues)；不可用/失败返回 None。"""
        if not self.available():
            return None
        project_key = f"evocode-{project_id}"
        try:
            self._run_scanner(project_key, code_dir)
            self._wait_ce(project_key)
            metrics = self._fetch_metrics(project_key)
            issues = self._fetch_issues(project_key)
            return metrics, issues
        except Exception as exc:
            logger.warning("Sonar 扫描失败，质量分析降级 N/A：%s", exc)
            return None

    def _auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"}

    def _run_scanner(self, project_key: str, code_dir: str) -> None:
        args = [
            self._scanner,
            f"-Dsonar.host.url={self._host}",
            # 审查 L3：token 改环境变量注入（避免出现在进程命令行，同机进程可读）
            f"-Dsonar.projectKey={project_key}",
            f"-Dsonar.projectName={project_key}",
            "-Dsonar.sources=.",
            f"-Dsonar.exclusions={_EXCLUSIONS}",
            "-Dsonar.sourceEncoding=UTF-8",
            "-Dsonar.scm.disabled=true",  # 单仓扫描不需要 git 元信息
        ]
        env = dict(os.environ)
        if self._token:
            env["SONAR_TOKEN"] = self._token
        result = subprocess.run(
            args,
            cwd=code_dir,
            env=env,
            capture_output=True,
            text=True,
            timeout=self._timeout * 4,  # scanner 本身耗时较长
        )
        if result.returncode != 0:
            tail = (result.stdout or "")[-800:] + (result.stderr or "")[-400:]
            raise RuntimeError(f"sonar-scanner 退出码 {result.returncode}: {tail}")
        logger.info("Sonar 扫描提交完成 project=%s", project_key)

    def _wait_ce(self, project_key: str) -> None:
        """轮询 Compute Engine 直到 SUCCESS；FAILED/CANCELED 视为扫描失败。"""
        with httpx.Client(timeout=self._timeout) as client:
            for _ in range(self._ce_poll_max):
                resp = client.get(
                    f"{self._host}/api/ce/component",
                    params={"component": project_key},
                    headers=self._auth(),
                )
                resp.raise_for_status()
                tasks = resp.json().get("current", []) or []
                task = tasks[0] if tasks else {}
                status = task.get("status", "PENDING")
                if status == "SUCCESS":
                    return
                if status in ("FAILED", "CANCELED"):
                    raise RuntimeError(f"Sonar CE 任务结束：{status}")
                time.sleep(self._ce_poll_seconds)
        raise RuntimeError("Sonar CE 轮询超时")

    def _fetch_metrics(self, project_key: str) -> dict[str, Any]:
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.get(
                f"{self._host}/api/measures/component",
                params={"component": project_key, "metricKeys": _METRIC_KEYS},
                headers=self._auth(),
            )
            resp.raise_for_status()
            measures = resp.json().get("component", {}).get("measures", [])
        values = {m["metric"]: m.get("value") for m in measures}
        return {
            "bugs": _to_int(values.get("bugs")),
            "vulnerabilities": _to_int(values.get("vulnerabilities")),
            "codeSmells": _to_int(values.get("code_smells")),
            "duplicationRate": _to_float(values.get("duplicated_lines_density")),
            "coverageRate": _to_float(values.get("coverage")),
            "complexity": _to_float(values.get("complexity")),
            "available": True,
        }

    def _fetch_issues(self, project_key: str) -> list[dict[str, Any]]:
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.get(
                f"{self._host}/api/issues/search",
                params={
                    "componentKeys": project_key,
                    "types": _ISSUE_TYPES,
                    "ps": 100,
                },
                headers=self._auth(),
            )
            resp.raise_for_status()
            raw = resp.json().get("issues", [])
        prefix = project_key + ":"
        out = []
        for i in raw:
            path = str(i.get("component") or "")
            if path.startswith(prefix):
                path = path[len(prefix) :]
            rule_key = str(i.get("rule") or "")
            message = str(i.get("message") or "")
            impact, fix = advice_for(rule_key, path, message)
            out.append(
                {
                    "ruleKey": rule_key,
                    "severity": str(i.get("severity") or "INFO"),
                    "kind": str(i.get("type") or "CODE_SMELL"),
                    "filePath": path,
                    "line": i.get("line"),
                    "message": message,
                    "suggestion": f"【影响】{impact}【修复】{fix}",
                }
            )
        return out


def _to_int(value: Any) -> int | None:
    try:
        return int(float(value)) if value is not None else None
    except (TypeError, ValueError):
        return None


def _to_float(value: Any) -> float | None:
    try:
        return round(float(value), 2) if value is not None else None
    except (TypeError, ValueError):
        return None


def quality_scan(
    project_id: int, code_dir: str, settings: Any
) -> dict[str, Any] | None:
    """编排入口：返回 QualityResult dict 或 None（不可用）。

    settings 提供 sonar 配置。
    """
    client = SonarClient(
        host_url=settings.sonar_host_url,
        token=settings.sonar_token,
        scanner=settings.sonar_scanner,
        timeout_seconds=settings.sonar_timeout_seconds,
        ce_poll_seconds=settings.sonar_ce_poll_seconds,
        ce_poll_max=settings.sonar_ce_poll_max,
    )
    return client.scan(project_id, code_dir)
