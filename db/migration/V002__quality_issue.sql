-- V002: 质量 issue 落库（docs/07-数据字典.md §3.5）
-- ai_explanation/ai_suggestion 由 P3d（/analyze/v1/explain）异步回填
CREATE TABLE quality_issue (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES project(id),
    analysis_id   BIGINT NOT NULL REFERENCES analysis(id),
    source        VARCHAR(20) NOT NULL,                -- SONAR
    severity      VARCHAR(10) NOT NULL,                -- BLOCKER/CRITICAL/MAJOR/MINOR/INFO
    kind          VARCHAR(20) NOT NULL,                -- BUG/VULNERABILITY/SMELL
    rule_key      VARCHAR(100),
    file_path     TEXT,
    line          INT,
    message       TEXT,
    ai_explanation TEXT,
    ai_suggestion  TEXT,
    ai_status     VARCHAR(10) NOT NULL DEFAULT 'PENDING', -- PENDING/DONE/FAILED
    status        VARCHAR(10) NOT NULL DEFAULT 'OPEN',    -- OPEN/IGNORED/FIXED
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_quality_issue_analysis ON quality_issue (project_id, analysis_id);
