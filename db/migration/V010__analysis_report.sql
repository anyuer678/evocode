-- SPI-6（A2）：报告拆表——health_score/level/summary 列化 + report_json 独立存储
-- 报告从 analysis 表拆出到 analysis_report：列表排序走 health_score 列（替代 report_json->>'healthScore' 子查询），
-- 报告与分析状态解耦（AD-13 演进，ReportService 为唯一改动点）。
CREATE TABLE IF NOT EXISTS analysis_report (
  id            BIGSERIAL PRIMARY KEY,
  analysis_id   BIGINT NOT NULL UNIQUE REFERENCES analysis(id),
  health_score  INT,                                    -- 健康分（列表排序列；数值防御，非数字为 NULL）
  level         VARCHAR(20),                            -- EXCELLENT/GOOD/FAIR/POOR
  summary       TEXT,                                   -- 报告概述
  report_json   JSONB NOT NULL,                         -- 完整报告（AD-13 结构，详情/导出用）
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_analysis_report_analysis ON analysis_report(analysis_id);
CREATE INDEX IF NOT EXISTS idx_analysis_report_score ON analysis_report(health_score);

-- 存量搬移：已有 report_json 的分析迁移到 analysis_report（healthScore 数值防御同 P8 列表）
INSERT INTO analysis_report (analysis_id, health_score, level, summary, report_json)
SELECT a.id,
       (CASE WHEN (a.report_json ->> 'healthScore') ~ '^\d+(\.\d+)?$'
             THEN (a.report_json ->> 'healthScore')::numeric::int END),
       a.report_json ->> 'level',
       a.report_json ->> 'summary',
       a.report_json
FROM analysis a
WHERE a.report_json IS NOT NULL AND a.deleted = 0
ON CONFLICT (analysis_id) DO NOTHING;
