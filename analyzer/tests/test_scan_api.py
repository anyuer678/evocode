"""T-A-10~12：scan 路由（200 + 状态文件 / 404 codeDir / 无效请求 422）。"""

import json

from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app

client = TestClient(app)


def test_scan_returns_result_and_writes_status(tmp_path, monkeypatch):
    settings = get_settings()
    status_dir = tmp_path / "status"
    monkeypatch.setattr(settings, "status_dir", str(status_dir))
    code_dir = tmp_path / "code"
    code_dir.mkdir()
    (code_dir / "pom.xml").write_text(
        "<project><parent><artifactId>spring-boot-starter-parent</artifactId></parent></project>",
        encoding="utf-8",
    )
    (code_dir / "src").mkdir()
    (code_dir / "src" / "Main.java").write_text("class Main {}\n", encoding="utf-8")

    resp = client.post(
        "/analyze/v1/scan", json={"projectId": 7, "codeDir": str(code_dir)}
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["fileCount"] == 2
    assert body["locTotal"] == 2
    assert body["languages"]["Java"] == 50.0
    assert "Spring Boot" in body["frameworks"]
    assert body["hasBackend"] is True
    status_file = status_dir / "7.status.json"
    assert status_file.exists()
    payload = json.loads(status_file.read_text(encoding="utf-8"))
    assert payload["status"] == "READY"
    assert payload["result"]["fileCount"] == 2


def test_scan_missing_code_dir_returns_404(tmp_path, monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "status_dir", str(tmp_path / "status"))
    resp = client.post(
        "/analyze/v1/scan",
        json={"projectId": 1, "codeDir": str(tmp_path / "nope")},
    )
    assert resp.status_code == 404


def test_scan_invalid_body_returns_422():
    resp = client.post("/analyze/v1/scan", json={"projectId": "x"})
    assert resp.status_code == 422
