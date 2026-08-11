-- V005__tech_doc.sql
-- 技术债 + 生成文档（docs/07-数据字典.md §3.11/§3.12），P6 建表（功能留 P7）
CREATE TABLE IF NOT EXISTS tech_debt (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL,
    source         VARCHAR(30)  NOT NULL,          -- ARCH / QUALITY / DEPEND / EVOLUTION / AI_DOCTOR / MANUAL
    title          VARCHAR(200) NOT NULL,
    level          VARCHAR(10)  NOT NULL,          -- HIGH / MEDIUM / LOW
    description    TEXT,
    suggestion     TEXT,
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN',  -- OPEN / DOING / DONE / WONTFIX
    ref_analysis_id BIGINT,
    resolve_note   TEXT,
    wonfix_reason  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_tech_debt_project ON tech_debt (project_id);
CREATE INDEX IF NOT EXISTS idx_tech_debt_status ON tech_debt (project_id, status);

CREATE TABLE IF NOT EXISTS generated_doc (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT       NOT NULL,
    doc_type    VARCHAR(20)  NOT NULL,             -- README / ARCH / API
    title       VARCHAR(200) NOT NULL,
    content     TEXT         NOT NULL,
    version     INT          NOT NULL DEFAULT 1,
    edited      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_generated_doc_project ON generated_doc (project_id);
