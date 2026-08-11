-- V008__chat_rag_fix.sql
-- 增量修复（端到端验证发现）：对已应用过 V006 旧版本的库补齐
-- pgvector 扩展 / deleted 列 / analysis_id 可空（全新库由 V006 一次到位，本迁移幂等无害）
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE knowledge_chunk ALTER COLUMN analysis_id DROP NOT NULL;
