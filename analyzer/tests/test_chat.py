"""P6b AI 医生 chat 生成端测试（06 §4 SSE 协议 / §5.7）。
无真 LLM/PG，mock 检索与流式。
"""

from __future__ import annotations

import json
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.core.rag.service import RagService
from app.main import app

client = TestClient(app)

_HITS = [
    {
        "file": "src/UserService.java",
        "chunkIndex": 0,
        "content": "public class UserService { public void save() { ... } }",
        "meta": {"symbol": "UserService", "lang": "java",
                 "startLine": 10, "endLine": 30},
        "score": 0.9,
    },
    {
        "file": "pom.xml",
        "chunkIndex": 0,
        "content": "<parent>2.5.14</parent>",
        "meta": {"symbol": None, "lang": "other",
                 "startLine": 1, "endLine": 50},
        "score": 0.5,
    },
]


def _sse_events(resp) -> list[tuple[str, dict]]:
    """解析 text/event-stream 响应为 [(event, data_dict)]。"""
    events = []
    for line in resp.iter_lines():
        line = (line or "").strip()
        if line.startswith("event: "):
            events.append((line[7:], None))
        elif line.startswith("data: "):
            events[-1] = (events[-1][0], json.loads(line[6:]))
    return events


class TestExtractCitations:
    def _svc(self) -> RagService:
        from app.config import get_settings
        from app.core.rag.vectorstore import KnowledgeStore

        return RagService(get_settings(), KnowledgeStore(""), None)  # type: ignore[arg-type]

    def test_keep_valid_and_drop_unknown(self) -> None:
        svc = self._svc()
        answer = (
            "问题在 [src/UserService.java:12] 与 [pom.xml:3]。"
            "[fake/NotExist.java:1] 是编造的。"
        )
        cites = svc._extract_citations(answer, _HITS)
        files = [(c["file"], c["line"]) for c in cites]
        assert ("src/UserService.java", 12) in files
        assert ("pom.xml", 3) in files
        assert "fake/NotExist.java" not in [f for f, _ in files]

    def test_drop_line_outside_chunk(self) -> None:
        svc = self._svc()
        answer = "[src/UserService.java:99] 超出符号区间"
        cites = svc._extract_citations(answer, _HITS)
        assert cites == []

    def test_basename_fallback_and_empty(self) -> None:
        svc = self._svc()
        answer = "看 [UserService.java:15]"
        cites = svc._extract_citations(answer, _HITS)
        assert cites and cites[0]["file"] == "src/UserService.java"
        assert svc._extract_citations("无引用", _HITS) == []


class TestChatSse:
    def test_fallback_when_no_hits(self) -> None:
        """检索不可用 → 兜底话术 + done，不调 LLM。"""
        body = {
            "projectId": 1,
            "systemContext": {"projectSummary": "s", "latestReportSummary": "r"},
            "history": [],
            "query": "为什么维护困难？",
        }
        with client.stream("POST", "/analyze/v1/chat", json=body) as resp:
            assert resp.status_code == 200
            assert resp.headers["content-type"].startswith("text/event-stream")
            events = _sse_events(resp)
        names = [e for e, _ in events]
        assert "delta" in names and "done" in names
        delta = next(d for e, d in events if e == "delta")
        assert "当前分析范围无法确认" in delta["content"]
        assert "citations" not in names

    def test_error_event_when_llm_stream_fails(self) -> None:
        """检索有结果但 LLM 流式失败 → error 事件（LLM_FAILED）。"""
        body = {
            "projectId": 1,
            "systemContext": {},
            "history": [],
            "query": "问题在哪？",
        }
        with patch(
            "app.core.rag.service.RagService.search",
            return_value=_HITS,
        ), patch(
            "app.main._rag._llm",
        ) as fake_llm:
            fake_llm.chat_stream.side_effect = RuntimeError("boom")
            with client.stream("POST", "/analyze/v1/chat", json=body) as resp:
                events = _sse_events(resp)
        names = [e for e, _ in events]
        assert names == ["error"]
        code = events[0][1]["code"]
        assert code == "LLM_FAILED"

    def test_delta_citations_done_flow(self) -> None:
        """正常流：delta... → citations → done；引用校验后输出。"""
        body = {
            "projectId": 1,
            "systemContext": {},
            "history": [],
            "query": "服务层如何拆分？",
        }
        stream = iter(
            [
                "服务层拆分建议：",
                "见 [src/UserService.java:15]；",
                "升级见 [pom.xml:3]。",
            ]
        )
        with patch(
            "app.core.rag.service.RagService.search",
            return_value=_HITS,
        ), patch(
            "app.main._rag._llm",
        ) as fake_llm:
            fake_llm.available.return_value = True
            fake_llm.chat_stream.return_value = stream
            with client.stream("POST", "/analyze/v1/chat", json=body) as resp:
                events = _sse_events(resp)
        names = [e for e, _ in events]
        assert names[0] == "delta"
        assert "citations" in names and names[-1] == "done"
        cites = next(d for e, d in events if e == "citations")
        items = cites["items"]
        files = [(i["file"], i["line"]) for i in items]
        assert ("src/UserService.java", 15) in files
        assert ("pom.xml", 3) in files
        full = "".join(
            d["content"] for e, d in events if e == "delta"
        )
        assert full == (
            "服务层拆分建议：见 [src/UserService.java:15]；"
            "升级见 [pom.xml:3]。"
        )
