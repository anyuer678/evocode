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
        assert result["source"] == "LLM"

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

    # ---- TD-08：规则版降级 ----

    def test_rules_fallback_when_llm_unavailable(self) -> None:
        class NoLlm:
            def available(self) -> bool:
                return False

            def chat_json(self, system, user):
                raise AssertionError("不应调用 LLM")

        result = generate_doc(
            NoLlm(),
            "README",
            scan={"languages": {"Python": 100.0}, "locTotal": 42,
                  "fileCount": 3, "frameworks": []},
            arch=None,
            project_info={"name": "demo", "description": "示例项目"},
            code_dir=None,
        )
        assert result["source"] == "RULES"
        assert result["title"] == "demo 使用说明"
        assert "规则引擎" in result["content"]
        assert "Python 100.0%" in result["content"]

    def test_rules_fallback_when_llm_fails(self, tmp_path) -> None:
        class BoomLlm:
            def available(self) -> bool:
                return True

            def chat_json(self, system, user):
                raise RuntimeError("LLM 调用失败（/v1/chat/completions）：500")

        result = generate_doc(
            BoomLlm(),
            "API",
            scan=None, arch=None, project_info={},
            code_dir=str(tmp_path),
        )
        assert result["source"] == "RULES"
        assert result["title"] == "API 文档"

    def test_api_rules_table_from_controllers(self, tmp_path) -> None:
        (tmp_path / "UserController.java").write_text(_JAVA, encoding="utf-8")
        result = generate_doc(
            type("NoLlm", (), {"available": lambda self: False,
                                "chat_json": lambda *a: None})(),
            "API",
            scan=None, arch=None, project_info={},
            code_dir=str(tmp_path),
        )
        assert result["source"] == "RULES"
        assert "GET" in result["content"]
        assert "/api/v1/users" in result["content"]


class TestDocRoute:
    def test_invalid_doc_type_400(self) -> None:
        resp = client.post("/analyze/v1/doc",
                           json={"projectId": 1, "docType": "XXX"})
        assert resp.status_code == 400

    def test_llm_no_key_rules_fallback_200(self) -> None:
        """TD-08：无 Key → 200 + 规则版文档（不再 400 LLM_NO_KEY）。"""
        with patch("app.main._llm") as fake:
            fake.available.return_value = False
            resp = client.post("/analyze/v1/doc",
                               json={"projectId": 1, "docType": "README",
                                     "projectInfo": {"name": "demo"}})
        assert resp.status_code == 200
        body = resp.json()
        assert body["source"] == "RULES"
        assert "规则引擎" in body["content"]

    def test_llm_failure_falls_back_200(self) -> None:
        with patch("app.main._llm") as fake:
            fake.available.return_value = True
            fake.chat_json.side_effect = RuntimeError(
                "LLM 调用失败（/v1/chat/completions）：500"
            )
            resp = client.post("/analyze/v1/doc",
                               json={"projectId": 1, "docType": "README"})
        assert resp.status_code == 200
        assert resp.json()["source"] == "RULES"

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
        assert body["source"] == "LLM"
