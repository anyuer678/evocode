-- 审查修复：并发 create 的 TOCTOU 竞态兜底——同一项目同时仅允许一条 PENDING/RUNNING
-- （AnalysisServiceImpl.create 的 selectCount 检查与 insert 之间无锁，并发 POST 可双 RUNNING）
CREATE UNIQUE INDEX IF NOT EXISTS uq_analysis_project_active
  ON analysis(project_id)
  WHERE status IN ('PENDING', 'RUNNING') AND deleted = 0;
