"""RAG 编排（P6，docs/02-开发指导.md §9.2/§9.3）。

- index：扫描 code_dir → 语言过滤 → 切片 → embedding（失败降级 NULL）→ store.rebuild
- search：查询 embedding（失败降级关键词）→ store.search
"""

from __future__ import annotations

import logging
import os
import re
from typing import Any

from ...config import Settings
from ..langdetect import detect_language
from ..llm import OpenAICompatClient
from ..prompts import build_doctor_prompt
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
                if detected.lower() not in languages or not supported_language(
                    detected
                ):
                    continue
                try:
                    with open(path, encoding="utf-8", errors="replace") as fh:
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
    ) -> list[dict[str, Any]]:
        query_embedding: list[float] | None = None
        if self._llm.available():
            try:
                query_embedding = self._llm.embed([query])[0]
            except Exception as exc:
                logger.warning("查询向量化失败，降级关键词：%s", exc)
        return self._store.search(project_id, query, top_k, query_embedding)

    # ---- chat（SSE 生成端，06 §5.7 / §4）----
    def chat(
        self,
        project_id: int,
        query: str,
        system_context: dict,
        history: list[dict],
        file_ref: dict | None = None,
    ):
        """AI 医生 SSE 生成器：yield {"event", "data"}，协议见 06 §4.2。"""
        hits: list[dict[str, Any]] = []
        try:
            hits = self.search(project_id, query, top_k=8)
        except Exception as exc:  # PG 未配置或宕机（psycopg.Error）→ 检索不可用
            logger.warning("chat 检索不可用：%s", exc)

        if not hits:
            # 检索为空 → 兜底话术（不调 LLM，AD-P6-5）
            yield {
                "event": "delta",
                "data": {
                    "content": (
                        "当前分析范围无法确认。建议先发起一次新分析"
                        "（或确认 RAG 知识库已建立），再向我提问。"
                    )
                },
            }
            yield {"event": "done", "data": {}}
            return

        knowledge_chunks = self._format_chunks(hits)
        history_text = "\n".join(
            f"{'用户' if h.get('role') == 'user' else '助手'}："
            f"{h.get('content', '')}"
            for h in history[-6:]  # 防御性截断（backend 已截断）
        )
        system, user = build_doctor_prompt(
            project_name=system_context.get("projectName") or "未知项目",
            language=system_context.get("language") or "未知",
            framework=system_context.get("framework") or "未知",
            loc=int(system_context.get("loc") or 0),
            project_summary=system_context.get("projectSummary") or "",
            latest_report_summary=system_context.get("latestReportSummary") or "",
            knowledge_chunks=knowledge_chunks,
            history=history_text,
            query=query,
            file_ref=file_ref,
        )

        answer_parts: list[str] = []
        try:
            for delta in self._llm.chat_stream(system, user, temperature=0.7):
                answer_parts.append(delta)
                yield {"event": "delta", "data": {"content": delta}}
        except Exception as exc:
            code = "LLM_NO_KEY" if "LLM_NO_KEY" in str(exc) else "LLM_FAILED"
            logger.warning("chat 流式失败 %s：%s", code, exc)
            yield {
                "event": "error",
                "data": {"code": code, "message": f"回答生成失败：{exc}"},
            }
            return

        answer = "".join(answer_parts)
        citations = self._extract_citations(answer, hits)
        yield {"event": "citations", "data": {"items": citations}}
        yield {"event": "done", "data": {}}

    @staticmethod
    def _format_chunks(hits: list[dict[str, Any]]) -> str:
        lines = []
        for hit in hits:
            meta = hit.get("meta") or {}
            start = meta.get("startLine") or 1
            symbol = meta.get("symbol")
            prefix = f"[{hit['file']}:{start}]"
            if symbol:
                prefix += f" ({symbol})"
            content = (hit.get("content") or "").strip()
            lines.append(f"{prefix}\n{content[:600]}")
        return "\n\n".join(lines)

    @staticmethod
    def _extract_citations(
        answer: str, hits: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        """从回答提取 [path:line] 引用并校验 ∈ 检索集合（AD-P6-5 防幻觉）。"""
        refs: list[tuple[str, int]] = []
        for match in re.finditer(r"\[([^\]\\(]+?):(\d+)\]", answer):
            path, line = match.group(1).strip(), int(match.group(2))
            if not path or line <= 0:
                continue
            refs.append((path, line))
        if not refs:
            return []
        by_basename = {os.path.basename(h["file"]): h for h in hits}
        citations: list[dict[str, Any]] = []
        seen: set[tuple[str, int]] = set()
        for path, line in refs:
            if (path, line) in seen:
                continue
            hit = None
            for h in hits:
                if h["file"] == path:
                    hit = h
                    break
            if hit is None:
                hit = by_basename.get(os.path.basename(path))
            if hit is None:
                continue  # 引用不在检索集合 → 剔除
            meta = hit.get("meta") or {}
            start, end = meta.get("startLine"), meta.get("endLine")
            if start and end and not (start <= line <= end):
                continue  # 行号不在 chunk 区间 → 剔除
            seen.add((path, line))
            citations.append(
                {
                    "file": hit["file"],
                    "line": line,
                    "excerpt": (hit.get("content") or "")[:200],
                }
            )
        return citations[:8]
