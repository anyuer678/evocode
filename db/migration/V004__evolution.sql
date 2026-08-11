-- V004__evolution.sql
-- 演化统计结果（docs/07-数据字典.md §3.9/3.10），P5
-- 注：07 字典未列 analysis_id，本实现按分析维度落库（与 arch_* 一致），同 analysis 重跑先删后插

CREATE TABLE IF NOT EXISTS commit_stat (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT        NOT NULL,
    analysis_id   BIGINT        NOT NULL,
    commit_hash   VARCHAR(40)   NOT NULL,
    author_name   VARCHAR(100),
    author_email  VARCHAR(200),
    committed_at  TIMESTAMPTZ   NOT NULL,
    lines_added   INT           NOT NULL DEFAULT 0,
    lines_removed INT           NOT NULL DEFAULT 0,
    files_changed INT           NOT NULL DEFAULT 0,
    message       TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_commit_stat UNIQUE (analysis_id, commit_hash)
);

CREATE INDEX IF NOT EXISTS idx_commit_stat_project ON commit_stat (project_id);
CREATE INDEX IF NOT EXISTS idx_commit_stat_analysis ON commit_stat (analysis_id);

CREATE TABLE IF NOT EXISTS file_change_stat (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT         NOT NULL,
    analysis_id   BIGINT         NOT NULL,
    file_path     TEXT           NOT NULL,
    commit_count  INT            NOT NULL DEFAULT 0,
    lines_added   INT            NOT NULL DEFAULT 0,
    lines_removed INT            NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_file_change UNIQUE (analysis_id, file_path)
);

CREATE INDEX IF NOT EXISTS idx_file_change_project ON file_change_stat (project_id);
CREATE INDEX IF NOT EXISTS idx_file_change_analysis ON file_change_stat (analysis_id);
