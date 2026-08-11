"""P6a RAG 切片与检索降级测试（docs/02 §9.1；真实 PG 依赖 Docker，单测走降级路径）。"""

from __future__ import annotations

import os

from fastapi.testclient import TestClient

from app.core.rag.chunker import chunk_source, normalize_language
from app.core.rag.vectorstore import extract_keywords
from app.main import app

client = TestClient(app)

_PY_SRC = '''\
"""模块 docstring。"""
import os
from typing import Optional


def helper(value: int) -> int:
    """小函数。"""
    return value + 1


class Service:
    def run(self, name: str) -> str:
        body = "x" * 4000
        return body + name
'''


class TestChunker:
    def test_python_symbols(self) -> None:
        chunks = chunk_source("svc.py", "Python", _PY_SRC)
        symbols = [c.symbol for c in chunks if c.symbol]
        assert "helper" in symbols
        assert "Service" in symbols
        # chunk_index 全局连续
        indexes = [c.chunk_index for c in chunks]
        assert indexes == list(range(len(chunks)))
        assert all(c.language == "python" for c in chunks)

    def test_long_symbol_slides(self) -> None:
        src = 'def long_fn():\n    return "' + "y" * 5000 + '"'
        chunks = chunk_source("long.py", "Python", src)
        long = [c for c in chunks if c.symbol == "long_fn"]
        assert len(long) > 1
        assert all(len(c.content) <= 3200 for c in long)

    def test_unsupported_language_fallback(self) -> None:
        chunks = chunk_source("app.js", "OTHER", "const x = 1;\n" * 100)
        assert chunks
        assert all(c.symbol is None for c in chunks)

    def test_module_remainder(self) -> None:
        src = "import os\n\n\ndef f():\n    return 1\n\n\n# 尾部注释\n"
        chunks = chunk_source("mod.py", "Python", src)
        module_chunks = [c for c in chunks if c.symbol is None]
        assert any("import os" in c.content for c in module_chunks)

    def test_normalize_language(self) -> None:
        assert normalize_language("Java") == "java"
        assert normalize_language("Python") == "python"
        assert normalize_language("OTHER") == "other"


class TestKeywords:
    def test_english_and_chinese(self) -> None:
        kw = extract_keywords("为什么 controller 层调用 service 慢？", limit=8)
        assert "controller" in kw
        assert "service" in kw
        cn_tokens = [
            k for k in kw if any("\u4e00" <= ch <= "\u9fff" for ch in k)
        ]
        assert any("为什么" in k or "调用" in k for k in cn_tokens)

    def test_stop_words_removed(self) -> None:
        kw = extract_keywords("how to fix this project", limit=8)
        assert "fix" in kw
        assert "how" not in kw and "this" not in kw

    def test_dedup_and_limit(self) -> None:
        kw = extract_keywords("error error error handler", limit=2)
        assert kw == ["error", "handler"]


class TestRagRoutes:
    def test_search_503_when_pg_unconfigured(self) -> None:
        resp = client.post(
            "/analyze/v1/rag/search",
            json={"projectId": 1, "query": "controller"},
        )
        assert resp.status_code == 503

    def test_index_404_when_dir_missing(self) -> None:
        resp = client.post(
            "/analyze/v1/rag/index",
            json={"projectId": 1, "codeDir": "Z:/no/such/dir"},
        )
        assert resp.status_code == 404

    def test_index_chunks_without_pg(self, tmp_path) -> None:
        """PG 未配置 → 切片产出但不入库（stored=false，200）。"""
        (tmp_path / "a.py").write_text(_PY_SRC, encoding="utf-8")
        resp = client.post(
            "/analyze/v1/rag/index",
            json={"projectId": 1, "codeDir": str(tmp_path).replace(os.sep, "/")},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["chunks"] > 0
        assert body["stored"] is False
        assert "未入库" in (body.get("message") or "")
