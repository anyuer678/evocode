"""RAG 向量存取（AD-P6-2：analyzer 直连 PG，只读写 knowledge_chunk 一张表）。

- rebuild：随 analysis 全量重建（先删同 analysis 旧块再插）
- search：向量 cosine（topN）＋ 关键词 LIKE（topN）合并去重 → topK
- embedding 不可用（未配置 key / 网关无模型）→ embedding 列存 NULL，检索纯关键词兜底
"""

from __future__ import annotations

import logging
import re
from typing import Any

import psycopg

from .chunker import CodeChunk

logger = logging.getLogger("evocode.analyzer.rag.store")

_STOP_WORDS = frozenset(
    {
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with",
        "is", "are", "was", "were", "be", "been", "this", "that", "these",
        "those", "it", "its", "as", "at", "by", "from", "how", "why", "what",
        "which", "who", "when", "where", "do", "does", "did", "can", "could",
        "should", "would", "will", "not", "no", "please", "get", "got", "make",
        "code", "project", "file", "class", "function",
    }
)


def extract_keywords(query: str, limit: int = 8) -> list[str]:
    """提取检索关键词：英文单词（≥3 chars，去停用词）＋ 中文连续段（≥2 chars）。"""
    tokens: list[str] = []
    for word in re.findall(r"[A-Za-z]{3,}", query.lower()):
        if word not in _STOP_WORDS:
            tokens.append(word)
    for seg in re.findall(r"[\u4e00-\u9fff]{2,}", query):
        tokens.append(seg)
    # 去重保序
    seen: set[str] = set()
    out: list[str] = []
    for token in tokens:
        if token not in seen:
            seen.add(token)
            out.append(token)
    return out[:limit]


class KnowledgeStore:
    """knowledge_chunk 直连存取。DSN 未配置 → available()=False（上层降级）。"""

    def __init__(self, dsn: str) -> None:
        self._dsn = (dsn or "").strip()

    def available(self) -> bool:
        return bool(self._dsn)

    def _connect(self) -> psycopg.Connection:
        return psycopg.connect(self._dsn, connect_timeout=5)

    def rebuild(
        self,
        project_id: int,
        analysis_id: int,
        chunks: list[CodeChunk],
        embeddings: list[list[float]] | None,
    ) -> int:
        """全量重建：删同 analysis 旧块 → 批量插入。返回插入条数。"""
        if not self.available():
            raise RuntimeError(
                "RAG 存储不可用：ANALYZER_PG_DSN 未配置"
            )
        has_vector = embeddings is not None and len(embeddings) == len(chunks)
        with self._connect() as conn, conn.cursor() as cur:
            # 项目级全量重建（analysis_id 仅记录来源；
            # 手动索引 analysis_id=None 同样幂等）
                cur.execute(
                    "DELETE FROM knowledge_chunk WHERE project_id = %s",
                    (project_id,),
                )
                rows = []
                for idx, chunk in enumerate(chunks):
                    embedding = embeddings[idx] if has_vector else None
                    rows.append(
                        (
                            project_id,
                            analysis_id,
                            chunk.file_path,
                            chunk.chunk_index,
                            chunk.content,
                            _json_dumps(
                                {
                                    "symbol": chunk.symbol,
                                    "lang": chunk.language,
                                    "startLine": chunk.start_line,
                                    "endLine": chunk.end_line,
                                }
                            ),
                            str(embedding) if embedding else None,
                        )
                    )
                cur.executemany(
                    "INSERT INTO knowledge_chunk "
                    "(project_id, analysis_id, file_path, chunk_index, content, "
                    "meta, embedding) VALUES (%s, %s, %s, %s, %s, %s, %s::vector)",
                    rows,
                )
                conn.commit()
                logger.info(
                    "RAG rebuild project=%s analysis=%s chunks=%s vector=%s",
                    project_id, analysis_id, len(rows), has_vector,
                )
                return len(rows)

    def search(
        self,
        project_id: int,
        query: str,
        top_k: int = 8,
        query_embedding: list[float] | None = None,
    ) -> list[dict[str, Any]]:
        """向量 cosine（有 query_embedding 时）＋ 关键词 LIKE 合并，
        score 降序取 top_k。
        """
        if not self.available():
            raise RuntimeError(
                "RAG 存储不可用：ANALYZER_PG_DSN 未配置"
            )
        keywords = extract_keywords(query)
        vector_hits: list[dict[str, Any]] = []
        keyword_hits: list[dict[str, Any]] = []

        with self._connect() as conn, conn.cursor() as cur:
            # 1) 向量检索：查询向量可用时按 cosine 排序
                if query_embedding:
                    cur.execute(
                        """
                        SELECT file_path, chunk_index, content, meta,
                               1 - (embedding <=> %s::vector) AS score
                        FROM knowledge_chunk
                        WHERE project_id = %s AND embedding IS NOT NULL
                        ORDER BY embedding <=> %s::vector
                        LIMIT %s
                        """,
                        (
                            str(query_embedding),
                            project_id,
                            str(query_embedding),
                            top_k,
                        ),
                    )
                    vector_hits = [
                        _row_to_hit(row) for row in cur.fetchall() if row[4] is not None
                    ]
                # 2) 关键词检索（LIKE）
                if keywords:
                    like = [f"%{k}%".lower() for k in keywords]
                    where = " OR ".join(
                        ["file_path ILIKE %s"] * len(keywords)
                        + ["content ILIKE %s"] * len(keywords)
                    )
                    params: list[Any] = [project_id]
                    for k in like:
                        params.append(k)
                        params.append(k)
                    cur.execute(
                        f"""
                        SELECT file_path, chunk_index, content, meta, 0.5 AS score
                        FROM knowledge_chunk
                        WHERE project_id = %s AND ({where})
                        ORDER BY chunk_index
                        LIMIT %s
                        """,
                        (*params, top_k),
                    )
                    keyword_hits = [_row_to_hit(row) for row in cur.fetchall()]

        # 合并去重（file_path + chunk_index 为键），score 降序
        merged: dict[tuple[str, int], dict[str, Any]] = {}
        for hit in vector_hits + keyword_hits:
            key = (hit["file"], hit["chunkIndex"])
            if key not in merged or hit["score"] > merged[key]["score"]:
                merged[key] = hit
        ranked = sorted(merged.values(), key=lambda h: h["score"], reverse=True)
        return ranked[:top_k]


def _json_dumps(obj: Any) -> str:
    import json

    return json.dumps(obj, ensure_ascii=False)


def _row_to_hit(row: tuple) -> dict[str, Any]:
    file_path, chunk_index, content, meta, score = row
    meta_obj = meta if isinstance(meta, dict) else {}
    return {
        "file": file_path,
        "chunkIndex": chunk_index,
        "content": content,
        "meta": meta_obj,
        "score": round(float(score), 4),
    }
