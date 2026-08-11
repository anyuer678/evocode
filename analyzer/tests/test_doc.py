"""P7b 文档生成测试（06 §5.9 契约新增；mock LLM，验证路由/输入拼装/端点提取）。"""

from __future__ import annotations

from unittest.mock import patch

from fastapi.testclient import TestClient

from app.core.docgen import _extract_controllers, generate_doc
from app.main import app

client = TestClient(app)

_JAVA = """\
package demo;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserController {

    @GetMapping("/users")
    public List<User> listUsers() {
        return null;
    }

    @PostMapping("/users")
    public User createUser(@RequestBody User u) {
        return u;
    }
}
"""


class TestExtractControllers:
    def test_extracts_endpoints(self, tmp_path) -> None:
        (tmp_path / "UserController.java").write_text(_JAVA, encoding="utf-8")
        lines = _extract_controllers(str(tmp_path))
        joined = "\n".join(lines)
        assert "UserController.java" in joined
        assert "GET /api/v1/users" in joined
        assert "POST /api/v1/users" in joined
        assert "listUsers" in joined

    def test_non_controller_ignored(self, tmp_path) -> None:
        (tmp_path / "Service.java").write_text(
            "package demo; public class Service { public void run() {} }",
            encoding="utf-8",
        )
        assert _extract_controllers(str(tmp_path)) == []

    def test_missing_dir(self) -> None:
        assert _extract_controllers("Z:/no/such") == []


class FakeLlm:
    def __init__(self, result: dict) -> None:
        self._result = result

    def available(self) -> bool:
        return True

    def chat_json(self, system: str, user: str) -> dict:
        return self._result

    def embed(self, texts: list[str]) -> list[list[float]]:
        return []


class TestGenerateDoc:
    def test_readme(self) -> None:
        result = generate_doc(
            FakeLlm({"title": "Demo 说明", "content": "# Demo\n快速开始"}),
            "README",
            scan={
                "languages": {"Java": 100.0},
                "locTotal": 100,
                "frameworks": ["Spring"],
            },
            arch=None,
            project_info={"name": "demo", "description": "示例"},
            code_dir=None,
        )
        assert result["docType"] == "README"
        assert result["title"] == "Demo 说明"
        assert "快速开始" in result["content"]

    def test_arch_and_api(self) -> None:
        arch = generate_doc(
            FakeLlm({"title": "架构", "content": "分层"}),
            "ARCH",
            scan=None,
            arch={"nodes": [], "edges": []},
            project_info={},
            code_dir=None,
        )
        assert arch["docType"] == "ARCH"
        api = generate_doc(
            FakeLlm({"title": "API", "content": "表格"}),
            "API",
            scan=None,
            arch=None,
            project_info={},
            code_dir=None,
        )
        assert api["docType"] == "API"

    def test_invalid_type(self) -> None:
        try:
            generate_doc(FakeLlm({}), "XXX", scan=None, arch=None,
                         project_info={}, code_dir=None)
            raise AssertionError("应抛 ValueError")
        except ValueError:
            pass


class TestDocRoute:
    def test_invalid_doc_type_400(self) -> None:
        resp = client.post("/analyze/v1/doc",
                           json={"projectId": 1, "docType": "XXX"})
        assert resp.status_code == 400

    def test_llm_no_key_400(self) -> None:
        with patch("app.main._llm") as fake:
            fake.chat_json.side_effect = RuntimeError(
                "LLM_API_KEY 未配置（LLM_NO_KEY）"
            )
            resp = client.post("/analyze/v1/doc",
                               json={"projectId": 1, "docType": "README"})
        assert resp.status_code == 400
        body = resp.json()["detail"]
        assert body["code"] == "LLM_NO_KEY"

    def test_success(self) -> None:
        with patch("app.main._llm") as fake:
            fake.chat_json.return_value = {"title": "T", "content": "C"}
            resp = client.post(
                "/analyze/v1/doc",
                json={"projectId": 1, "docType": "README",
                      "projectInfo": {"name": "demo"}},
            )
        assert resp.status_code == 200
        body = resp.json()
        assert body["docType"] == "README"
        assert body["title"] == "T"
