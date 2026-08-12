"""T-E-01~05：P9e TD-01 issue 解释——规则版模板、severity 兜底、端点契约。"""

from fastapi.testclient import TestClient

from app.core.explain import explain
from app.main import app

client = TestClient(app)


def _issue(**overrides):
    issue = {
        "ruleKey": "java:S112",
        "severity": "BLOCKER",
        "kind": "BUG",
        "filePath": "src/main/java/com/demo/App.java",
        "line": 10,
        "message": "Generic exceptions should never be thrown",
    }
    issue.update(overrides)
    return issue


def test_rules_explain_hits_known_rule():
    # TD-01：命中规则表 → 规则版模板解释（无 LLM）
    result = explain(_issue(), None, llm=None)
    assert result["source"] == "RULES"
    assert "Generic exceptions" in result["explanation"]
    assert "位置" in result["explanation"]  # 含文件:行
    assert result["suggestion"]


def test_rules_explain_unknown_rule_uses_severity():
    # 未命中规则表 → severity 兜底 + 语言前缀，不抛错
    result = explain(_issue(ruleKey="java:S9999", severity="MINOR"), None, llm=None)
    assert result["source"] == "RULES"
    assert "Java 静态规则" in result["explanation"]
    assert "MINOR" in result["suggestion"]


def test_rules_explain_missing_line_ok():
    result = explain(_issue(line=None, filePath="App.java"), None, llm=None)
    assert result["source"] == "RULES"
    assert "位置：App.java" in result["explanation"]  # 无行号不拼接


def test_explain_endpoint_rules_source():
    resp = client.post("/analyze/v1/explain", json={
        "issue": _issue(),
        "fileSnippet": "throw new Exception();",
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["source"] == "RULES"
    assert body["explanation"]
    assert body["suggestion"]


def test_explain_endpoint_llm_source_when_available(monkeypatch):
    # LLM 可用 → source=LLM 增强（mock chat_json）
    class FakeLLM:
        def available(self):
            return True

        def chat_json(self, system, user):
            return {"explanation": "AI 解释", "suggestion": "AI 建议",
                    "codeExample": "AI 示例"}

    result = explain(_issue(), None, llm=FakeLLM())
    assert result["source"] == "LLM"
    assert result["explanation"] == "AI 解释"
    assert result["codeExample"] == "AI 示例"
