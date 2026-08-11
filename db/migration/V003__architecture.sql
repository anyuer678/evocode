-- V003__architecture.sql
-- 架构分析结果（docs/07-数据字典.md §3.6/3.7/3.8），P4

CREATE TABLE IF NOT EXISTS architecture_node (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT          NOT NULL,
    analysis_id BIGINT          NOT NULL,
    node_key    VARCHAR(256)    NOT NULL,
    name        VARCHAR(128)    NOT NULL,
    node_type   VARCHAR(32)     NOT NULL,          -- CONTROLLER/SERVICE/REPOSITORY/ENTITY/UTIL/MODULE/OTHER
    file_path   VARCHAR(1024)   NOT NULL,
    metrics     JSONB           NOT NULL DEFAULT '{}',  -- inDegree/outDegree
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_arch_node UNIQUE (analysis_id, node_key)
);

CREATE INDEX idx_arch_node_project ON architecture_node (project_id);
CREATE INDEX idx_arch_node_analysis ON architecture_node (analysis_id);

CREATE TABLE IF NOT EXISTS architecture_edge (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL,
    analysis_id    BIGINT       NOT NULL,
    source_node_id BIGINT       NOT NULL,
    target_node_id BIGINT       NOT NULL,
    relation       VARCHAR(16)  NOT NULL DEFAULT 'CALL',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_arch_edge UNIQUE (analysis_id, source_node_id, target_node_id, relation)
);

CREATE INDEX idx_arch_edge_project ON architecture_edge (project_id);
CREATE INDEX idx_arch_edge_analysis ON architecture_edge (analysis_id);

CREATE TABLE IF NOT EXISTS arch_violation (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL,
    analysis_id     BIGINT       NOT NULL,
    violation_type  VARCHAR(64)  NOT NULL,          -- LAYER_VIOLATION / ...
    description     TEXT         NOT NULL,
    source_node_id  BIGINT,                        -- 可空（全局性违规）
    target_node_id  BIGINT,
    severity        VARCHAR(16)  NOT NULL,          -- HIGH / MEDIUM / LOW
    suggestion      TEXT,
    ai_note         TEXT,                           -- v1.0 AI 医生补充，当前为 NULL
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_arch_violation_project ON arch_violation (project_id);
CREATE INDEX idx_arch_violation_analysis ON arch_violation (analysis_id);
