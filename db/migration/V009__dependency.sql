-- V009__dependency.sql
-- 依赖清单（docs/07-数据字典.md §3.4），P9d
-- 注：V002 无 dependency 表（设计稿 D2 需核对），此处按 07 §3.4 字段建表
-- 落库语义：同 analysis 重跑先删后插；available=false（无 Maven/npm 依赖文件）清空该项目
-- ai_advice/ai_status 为 AI 升级建议预留（P9 本期不启用，列保留供后续）

CREATE TABLE IF NOT EXISTS dependency (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT        NOT NULL,
    analysis_id     BIGINT        NOT NULL,
    ecosystem       VARCHAR(20)   NOT NULL,          -- maven / npm / pip / go
    name            VARCHAR(200)  NOT NULL,          -- maven 用 groupId:artifactId
    version         VARCHAR(50),
    latest_version  VARCHAR(50),                     -- 规则表建议的最新版本
    risk_level      VARCHAR(10),                     -- LOW/MEDIUM/HIGH；null=未命中规则（未知版本）
    risk_reason     TEXT,                            -- EOL 等风险原因
    suggestion      TEXT,                            -- 升级建议（本期=规则 reason）
    file            VARCHAR(100),                    -- 来源文件（pom.xml / package.json）
    is_eol          BOOLEAN       NOT NULL DEFAULT FALSE,
    ai_advice       JSONB,                           -- AI 升级建议 {impact,steps,risks,estimate}
    ai_status       VARCHAR(10)   NOT NULL DEFAULT 'NONE', -- NONE/PENDING/DONE/FAILED
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dependency_project ON dependency (project_id);
CREATE INDEX IF NOT EXISTS idx_dependency_analysis ON dependency (analysis_id);
