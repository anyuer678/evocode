"""T-A-10~16：规则版报告、LLM 版（修正±10）、降级与 /analyze/v1/report 端点。"""

import pytest
from fastapi.testclient import TestClient

from app.core.report_rules import build_rules_report
from app.core.reportgen import generate_report
from app.main import app
from app.schemas import ScanResult


def make_scan(
    loc_total: int = 5_000,
    files: int = 20,
    skipped: int = 0,
    truncated: bool = False,
    frameworks: list[str] | None = None,
    has_backend: bool = True,
    has_frontend: bool = True,
) -> ScanResult:
    return ScanResult(
        languages={"Python": 100.0},
        locTotal=loc_total,
        fileCount=files,
        ignoredCount=0,
        frameworks=frameworks or ["FastAPI"],
        hasBackend=has_backend,
        hasFrontend=has_frontend,
        dbHint=[],
        files=[],
        skippedBigFiles=skipped,
        truncated=truncated,
    )


class FakeLLM:
    """可控 LLM 客户端：返回预设 JSON 或抛异常。"""

    def __init__(
        self,
        available: bool = True,
        payload: dict | None = None,
        error: Exception | None = None,
    ):
        self._available = available
        self._payload = payload
        self._error = error

    def available(self) -> bool:
        return self._available

    def chat_json(self, system: str, user: str) -> dict:
        if self._error:
            raise self._error
        return self._payload


# ---- 规则版 ----
def test_rules_report_shape():
    scan = make_scan()
    report, source, base = generate_report(scan, llm=None)

    assert source == "RULES"
    assert 0 <= report["healthScore"] <= 100
    assert report["level"] in ("EXCELLENT", "GOOD", "FAIR", "POOR")
    assert [d["key"] for d in report["dimensions"]] == [
        "quality",
        "structure",
        "dependency",
        "scale",
    ]
    assert all(1 <= d["stars"] <= 5 for d in report["dimensions"])
    assert report["techStack"]["languages"] == {"Python": 100.0}
    assert report["scoreDetail"] == base["scoreDetail"]


def test_rules_report_with_sonar_metrics():
    """Sonar 接入：质量维度用真实指标评分，并生成漏洞/Bug 风险。"""
    scan = make_scan()
    quality = {
        "available": True,
        "bugs": 3,
        "vulnerabilities": 2,
        "codeSmells": 60,
        "duplicationRate": 15.0,
        "coverageRate": 30.0,
        "complexity": 6.0,
    }
    report, source, _ = generate_report(scan, llm=None, quality=quality)

    assert source == "RULES"
    q_dim = next(d for d in report["dimensions"] if d["key"] == "quality")
    # 85 - 3*3(bug) - 2*5(漏洞) - 0(异味<100) - 1(重复>10) - 8(覆盖率<50) - 5(复杂度>5)
    assert q_dim["score"] == 85 - 9 - 10 - 1 - 8 - 5
    assert "Sonar 扫描" in q_dim["summary"]
    assert any(r["title"] and "安全漏洞" in r["title"] for r in report["risks"])
    assert any(r["title"] and "Bug" in r["title"] for r in report["risks"])


def test_rules_report_penalizes_big_files():
    scan = make_scan(skipped=3, truncated=True)
    report, _, _ = generate_report(scan, llm=None)

    quality = report["scoreDetail"]["quality"]
    assert quality <= 60  # 75 - 3*5 - 5
    assert any(
        r["level"] == "HIGH" and "超大文件" in r["title"] for r in report["risks"]
    )


def test_rules_report_history_delta_in_summary():
    scan = make_scan()
    report, _, _ = generate_report(
        scan, history_reports=[{"healthScore": 100}], llm=None
    )
    assert "较上期" in report["summary"]


# ---- LLM 版 ----
def test_llm_report_used_and_clamped():
    scan = make_scan()
    base = build_rules_report(scan, []).health_score
    llm = FakeLLM(
        payload={
            "healthScore": base + 99,  # 远超 ±10 → 夹回
            "level": "EXCELLENT",
            "summary": "人工修正说明",
            "dimensions": [
                {"key": "quality", "score": 70, "summary": "a"},
                {"key": "structure", "score": 80, "summary": "b"},
                {"key": "dependency", "score": 70, "summary": "c"},
                {"key": "scale", "score": 90, "summary": "d"},
            ],
            "risks": [
                {
                    "level": "HIGH",
                    "title": "t",
                    "detail": "d",
                    "suggestion": "s",
                    "references": [],
                }
            ],
            "recommendations": [{"phase": "第一阶段", "items": ["x"]}],
        }
    )
    report, source, _ = generate_report(scan, llm=llm)

    assert source == "LLM"
    assert report["healthScore"] == base + 10  # clamp 到上限
    assert report["scoreDetail"] == build_rules_report(scan, []).score_detail


def test_llm_invalid_structure_falls_back():
    scan = make_scan()
    llm = FakeLLM(payload={"healthScore": 99, "dimensions": []})  # dimensions 数不对
    report, source, _ = generate_report(scan, llm=llm)

    assert source == "RULES"
    assert report["scoreDetail"] is not None


def test_llm_error_falls_back():
    scan = make_scan()
    llm = FakeLLM(error=RuntimeError("LLM_FAILED"))
    report, source, _ = generate_report(scan, llm=llm)

    assert source == "RULES"


def test_llm_no_key_falls_back():
    scan = make_scan()
    llm = FakeLLM(available=False)
    report, source, _ = generate_report(scan, llm=llm)

    assert source == "RULES"


# ---- 端点 ----
def test_report_endpoint_rules(client: TestClient):
    payload = {
        "projectId": 1,
        "scan": make_scan().model_dump(mode="json"),
        "historyReports": [],
        "regenerate": False,
    }
    resp = client.post("/analyze/v1/report", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body["source"] == "RULES"
    assert body["promptVersion"]
    assert body["report"]["healthScore"] >= 0
    assert len(body["report"]["dimensions"]) == 4


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)
