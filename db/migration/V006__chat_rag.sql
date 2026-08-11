-- V006__chat_rag.sql
-- 会话 + RAG 知识块（docs/07-数据字典.md §3.13/§3.14/§3.15），P6
-- pgvector 扩展：镜像内置 vector 类型，但需显式启用
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS chat_session (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    title       VARCHAR(200) NOT NULL DEFAULT '新会话',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_chat_session_project ON chat_session (project_id, created_at DESC);

CREATE TABLE IF NOT EXISTS chat_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT       NOT NULL,
    role        VARCHAR(10)  NOT NULL,             -- USER / ASSISTANT
    content     TEXT         NOT NULL,
    citations   JSONB,                             -- [{file,line,excerpt}]，ASSISTANT 才有
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message (session_id, id);

-- 兼容早期版本表（旧表缺 deleted 列，@TableLogic 需要）
ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

-- RAG 知识块：随 analysis 全量重建；embedding 维数随模型（默认 bge-m3 = 1024）
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    analysis_id BIGINT,                          -- 随 analysis 全量重建；手动索引可为 NULL
    file_path   TEXT         NOT NULL,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,             -- ≤800 token
    meta        JSONB,                             -- {symbol,lang}
    embedding   VECTOR(1024),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 兼容早期版本：analysis_id 曾为 NOT NULL
ALTER TABLE knowledge_chunk ALTER COLUMN analysis_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_kchunk_project ON knowledge_chunk (project_id);
CREATE INDEX IF NOT EXISTS idx_kchunk_analysis ON knowledge_chunk (analysis_id);
CREATE INDEX IF NOT EXISTS idx_kchunk_embedding ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
