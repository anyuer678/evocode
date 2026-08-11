"""T-A-20~24：Sonar 质量分析——不可用降级、全流程解析、端点。"""

from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.sonar import SonarClient
from app.main import app


class FakeResp:
    def __init__(self, payload: dict):
        self._p = payload

    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return self._p


class FakeProc:
    def __init__(self, returncode: int = 0, stdout: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = ""


class FakeClient:
    """httpx.Client 替代：按调用顺序出队响应。"""

    def __init__(self, responses: list[dict]):
        self._responses = list(responses)
        self.calls: list[str] = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def get(self, url: str, params: dict | None = None, headers: dict | None = None):
        self.calls.append(url)
        return FakeResp(self._responses.pop(0))

    def post(self, url: str, *args, **kwargs):
        self.calls.append(url)
        return FakeResp(self._responses.pop(0))


def make_client(**kw) -> SonarClient:
    defaults = {
        "host_url": "http://127.0.0.1:9000",
        "token": "test-token",
        "scanner": "sonar-scanner",
    }
    defaults.update(kw)
    return SonarClient(**defaults)


def full_responses() -> list[dict]:
    return [
        {"status": "UP"},
        {"current": [{"status": "SUCCESS"}]},
        {
            "component": {
                "measures": [
                    {"metric": "bugs", "value": "3"},
                    {"metric": "vulnerabilities", "value": "1"},
                    {"metric": "code_smells", "value": "12"},
                    {"metric": "duplicated_lines_density", "value": "5.2"},
                    {"metric": "coverage", "value": "80.1"},
                    {"metric": "complexity", "value": "3.5"},
                ]
            }
        },
        {
            "issues": [
                {
                    "rule": "java:S3776",
                    "severity": "MAJOR",
                    "type": "CODE_SMELL",
                    "component": "evocode-7:src/A.java",
                    "line": 12,
                    "message": "Method too long",
                }
            ]
        },
    ]


# ---- 不可用降级 ----
def test_no_token_unavailable():
    client = make_client(token="")
    assert not client.available()
    assert client.scan(7, "data/projects/7") is None


def test_no_scanner_unavailable():
    client = make_client(scanner="")
    assert not client.available()


def test_host_down_unavailable():
    fake = FakeClient([])

    def boom(*args, **kwargs):
        raise ConnectionError("refused")

    fake.get = boom
    with patch("app.core.sonar.httpx.Client", lambda **kw: fake):
        client = make_client()
        assert client.scan(7, "data/projects/7") is None


# ---- 全流程 ----
def test_full_scan_parses_metrics_and_issues():
    fake = FakeClient(full_responses())
    with (
        patch("app.core.sonar.httpx.Client", lambda **kw: fake),
        patch("app.core.sonar.subprocess.run") as run,
    ):
        run.return_value = FakeProc(0)
        result = make_client().scan(7, "data/projects/7")

    assert result is not None
    metrics, issues = result
    assert metrics["bugs"] == 3
    assert metrics["vulnerabilities"] == 1
    assert metrics["codeSmells"] == 12
    assert metrics["duplicationRate"] == 5.2
    assert metrics["coverageRate"] == 80.1
    assert metrics["complexity"] == 3.5
    assert metrics["available"] is True
    assert len(issues) == 1
    assert issues[0]["filePath"] == "src/A.java"
    assert issues[0]["ruleKey"] == "java:S3776"
    assert issues[0]["kind"] == "CODE_SMELL"


def test_scanner_failure_degrades_to_none():
    fake = FakeClient([{"status": "UP"}])
    with (
        patch("app.core.sonar.httpx.Client", lambda **kw: fake),
        patch("app.core.sonar.subprocess.run") as run,
    ):
        run.return_value = FakeProc(1, stdout="syntax error")
        result = make_client().scan(7, "data/projects/7")

    assert result is None


# ---- 端点 ----
@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


def test_quality_endpoint_unavailable(tmp_path: Path, client: TestClient):
    resp = client.post(
        "/analyze/v1/quality",
        json={"projectId": 1, "codeDir": str(tmp_path)},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["metrics"]["available"] is False
    assert body["issues"] == []


def test_quality_endpoint_with_metrics(monkeypatch, tmp_path: Path, client: TestClient):
    monkeypatch.setattr(
        "app.main.quality_scan",
        lambda project_id, code_dir, settings: {
            "metrics": {
                "bugs": 2,
                "vulnerabilities": 0,
                "codeSmells": 5,
                "duplicationRate": 1.2,
                "coverageRate": None,
                "complexity": 2.1,
                "available": True,
            },
            "issues": [],
        },
    )
    resp = client.post(
        "/analyze/v1/quality",
        json={"projectId": 1, "codeDir": str(tmp_path)},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["metrics"]["available"] is True
    assert body["metrics"]["bugs"] == 2
    assert body["metrics"]["complexity"] == 2.1
