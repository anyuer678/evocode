"""RAG 编排（P6，docs/02-开发指导.md §9.2/§9.3）。

- index：扫描 code_dir → 语言过滤 → 切片 → embedding（失败降级 NULL）→ store.rebuild
- search：查询 embedding（失败降级关键词）→ store.search
"""

from __future__ import annotations

import logging
import os
from typing import Any

from ...config import Settings
from ..langdetect import detect_language
from ..llm import OpenAICompatClient
from .chunker import CodeChunk, chunk_source, supported_language
from .vectorstore import KnowledgeStore

logger = logging.getLogger("evocode.analyzer.rag.service")

_INDEX_LANGUAGES = ("python", "java")
_MAX_FILE_BYTES = 1_048_576  # 1MB，防大文件爆切
_MAX_CHUNKS = 5000  # 单次索引上限
_EMBED_BATCH = 64


class RagService:
    def __init__(
        self,
        settings: Settings,
        store: KnowledgeStore,
        llm: OpenAICompatClient,
    ) -> None:
        self._settings = settings
        self._store = store
        self._llm = llm

    # ---- index ----
    def index(
        self,
        project_id: int,
        code_dir: str,
        languages: list[str] | None = None,
        analysis_id: int | None = None,
    ) -> dict[str, Any]:
        langs = {lang.lower() for lang in languages or list(_INDEX_LANGUAGES)}
        chunks = self._collect_chunks(code_dir, langs)
        if not chunks:
            return {"chunks": 0, "embeddingModel": None, "stored": False}
        embeddings = self._embed_all(chunks)
        model = self._settings.llm_embedding_model if embeddings is not None else None
        try:
            stored = self._store.rebuild(project_id, analysis_id, chunks, embeddings)
        except Exception as exc:  # PG 不可用 → 切片已产出但不入库（stored=false）
            logger.warning("RAG index 入库失败：%s", exc)
            return {
                "chunks": len(chunks),
                "embeddingModel": model,
                "stored": False,
                "message": f"切片已生成但未入库：{exc}",
            }
        return {"chunks": stored, "embeddingModel": model, "stored": True}

    def _collect_chunks(self, code_dir: str, languages: set[str]) -> list[CodeChunk]:
        chunks: list[CodeChunk] = []
        for root, _dirs, files in os.walk(code_dir):
            for name in files:
                if len(chunks) >= _MAX_CHUNKS:
                    break
                path = os.path.join(root, name)
                try:
                    size = os.path.getsize(path)
                except OSError:
                    continue
                if size <= 0 or size > _MAX_FILE_BYTES:
                    continue
                rel = os.path.relpath(path, code_dir).replace("\\", "/")
                detected = detect_language(rel)
                if (
                    detected.lower() not in languages
                    or not supported_language(detected)
                ):
                    continue
                try:
                    with open(
                        path, encoding="utf-8", errors="replace"
                    ) as fh:
                        source = fh.read()
                except OSError:
                    continue
                chunks.extend(chunk_source(rel, detected, source))
        return chunks

    def _embed_all(self, chunks: list[CodeChunk]) -> list[list[float]] | None:
        """批量向量化；任一失败 → None（关键词兜底，AD-P6-1）。"""
        if not self._llm.available():
            return None
        texts = [c.content for c in chunks]
        out: list[list[float]] = []
        try:
            for start in range(0, len(texts), _EMBED_BATCH):
                out.extend(self._llm.embed(texts[start : start + _EMBED_BATCH]))
        except Exception as exc:  # 网关无 embedding 模型 / 网络失败
            logger.warning("RAG embedding 不可用，降级关键词检索：%s", exc)
            return None
        return out

    # ---- search ----
    def search(
        self,
        project_id: int,
        query: str,
        top_k: int = 8,
        query_embedding: list[float] | None = None,
    ) -> list[dict[str, Any]]:
        query_embedding: list[float] | None = None
        if self._llm.available():
            try:
                query_embedding = self._llm.embed([query])[0]
            except Exception as exc:
                logger.warning("查询向量化失败，降级关键词：%s", exc)
        return self._store.search(project_id, query, top_k, query_embedding)
