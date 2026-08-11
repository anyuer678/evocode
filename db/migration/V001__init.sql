-- EvoCode V001__init.sql（v0.1：project / analysis / file_node）
-- 约定：UTF-8；主键 bigint；时间 TIMESTAMPTZ；逻辑删除 deleted；枚举存 varchar(20)；灵活结构 jsonb
-- 注：analysis 表在 02 §5 基线之上新增 report_source / prompt_version / regenerated_at
--     （05 第三轮审查 C-1/C-2：报告来源与重生成时间必须落库）

-- 项目
CREATE TABLE project (
  id               BIGSERIAL PRIMARY KEY,
  name             VARCHAR(100) NOT NULL,
  description      TEXT,
  source_type      VARCHAR(10)  NOT NULL,                 -- ZIP / GIT
  repo_url         VARCHAR(500),
  storage_path     VARCHAR(500) NOT NULL,                 -- data/projects/{id}/
  status           VARCHAR(20)  NOT NULL DEFAULT 'CREATED',  -- CREATED/ANALYZING/READY/FAILED
  lang_stats       JSONB,                                 -- {"Java":60,"Python":30,...}
  framework_tags   VARCHAR(100)[],                        -- {Vue,Electron}
  loc_total        BIGINT,
  file_count       INT,
  ignored_count    INT,                                   -- 被忽略文件数（报告说明）
  last_analyzed_at TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted          SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_project_deleted ON project(deleted);
CREATE INDEX idx_project_status ON project(status);

-- 分析任务（状态机）
CREATE TABLE analysis (
  id            BIGSERIAL PRIMARY KEY,
  project_id    BIGINT NOT NULL REFERENCES project(id),
  type          VARCHAR(20) NOT NULL DEFAULT 'FULL',      -- FULL/QUALITY/ARCH/EVOLUTION
  status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED
  progress      INT NOT NULL DEFAULT 0,                   -- 0-100
  stage         VARCHAR(30),                              -- SCAN/REPORT/…
  error_code    VARCHAR(10),
  error_message TEXT,
  report_json   JSONB,                                    -- AI 报告（AD-13）
  report_source VARCHAR(10),                              -- LLM / RULES（报告来源，第三轮审查 C-1）
  prompt_version VARCHAR(20),                             -- 生成报告所用 prompt 版本（C-1）
  analyzer_version VARCHAR(20),
  regenerated_at TIMESTAMPTZ,                             -- 最近一次重新生成时间（C-2）
  started_at    TIMESTAMPTZ,
  finished_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted       SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_analysis_project ON analysis(project_id, id DESC);

-- 文件快照（项目地图）
CREATE TABLE file_node (
  id          BIGSERIAL PRIMARY KEY,
  project_id  BIGINT NOT NULL REFERENCES project(id),
  analysis_id BIGINT NOT NULL REFERENCES analysis(id),
  path        TEXT NOT NULL,
  language    VARCHAR(30),
  loc         INT,
  size_bytes  INT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_file_node ON file_node(project_id, analysis_id);
