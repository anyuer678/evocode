-- V007__hotspot.sql
-- 演化热点（docs/07-数据字典.md §3.16），P5
-- evidence 结构见 07 §5.6：["变更 45 次", ...]；ai_conclusion 由 v1.0 AI 医生可选填充

CREATE TABLE IF NOT EXISTS hotspot (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    analysis_id   BIGINT       NOT NULL,
    module        VARCHAR(200) NOT NULL,
    risk_level    VARCHAR(10)  NOT NULL,          -- HIGH / MEDIUM
    evidence      JSONB        NOT NULL DEFAULT '[]',
    ai_conclusion TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hotspot_project ON hotspot (project_id);
CREATE INDEX IF NOT EXISTS idx_hotspot_analysis ON hotspot (analysis_id);
