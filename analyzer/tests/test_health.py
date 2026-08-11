from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_ok() -> None:
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "evocode-analyzer"


def test_root_redirects_to_frontend_note() -> None:
    resp = client.get("/")
    assert resp.status_code == 200
    body = resp.json()
    assert body["service"] == "evocode-analyzer"
    assert "localhost:5173" in body["message"]
    assert "/analyze/v1/architecture" in body["endpoints"]


def test_unknown_route_404() -> None:
    assert client.get("/nope").status_code == 404
