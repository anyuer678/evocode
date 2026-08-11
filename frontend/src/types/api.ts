/**
 * 与后端 Result 对应的统一响应类型（docs/06-API契约.md §1）。
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/**
 * 通用分页响应：data 内 {total, page, size, items}（06 §1）。
 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  items: T[]
}

// ---------------- 项目（06 §3.1~3.4） ----------------

export type ProjectSourceType = 'ZIP' | 'GIT'

export type ProjectStatus = 'CREATED' | 'ANALYZING' | 'READY' | 'FAILED'

/** 创建项目响应（方式 A zip / 方式 B git，201） */
export interface ProjectResp {
  id: number
  name: string
  sourceType: ProjectSourceType
  status: ProjectStatus
  storagePath: string
  langStats: Record<string, number> | null
  locTotal: number
  fileCount: number
  frameworkTags: string[]
  lastAnalyzedAt: string | null
  createdAt: string
}

/** 项目列表项（06 §3.2，含 healthScore JOIN 子查询） */
export interface ProjectSummary {
  id: number
  name: string
  description: string | null
  sourceType: ProjectSourceType
  langStats: Record<string, number> | null
  frameworkTags: string[]
  locTotal: number
  fileCount: number
  status: ProjectStatus
  healthScore: number | null
  lastAnalyzedAt: string | null
  createdAt: string
}

/** 最近一次分析摘要（06 §3.3 latestAnalysis） */
export interface LatestAnalysis {
  id: number
  status: string
  stage: string | null
  progress: number | null
  startedAt: string | null
  finishedAt: string | null
}

/** 项目详情（06 §3.3） */
export interface ProjectDetail {
  id: number
  name: string
  description: string | null
  sourceType: ProjectSourceType
  repoUrl: string | null
  status: ProjectStatus
  langStats: Record<string, number> | null
  frameworkTags: string[]
  locTotal: number
  fileCount: number
  ignoredCount: number | null
  lastAnalyzedAt: string | null
  latestAnalysis: LatestAnalysis | null
  createdAt: string
}

// ---------------- 项目地图与文件内容（06 §3.8） ----------------

export interface FileNodeItem {
  path: string
  language: string
  loc: number
  sizeBytes: number
}

export interface FileContent {
  path: string
  language: string
  loc: number
  content: string
  truncated: boolean
}

// ---------------- 分析历史（06 §3.6，P1 详情页轮询用） ----------------

export type AnalysisStatusValue = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface AnalysisHistoryItem {
  id: number
  type: string
  status: AnalysisStatusValue
  progress: number
  stage: string | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  source: string | null
  healthScore: number | null
}

/** 单任务状态轮询（06 §3.6，前端 2s 间隔） */
export interface AnalysisStatus {
  id: number
  status: AnalysisStatusValue
  progress: number
  stage: string | null
  errorMessage: string | null
}

/** 发起分析响应（06 §3.5，202） */
export interface AnalysisCreated {
  id: number
  projectId: number
  type: string
  status: AnalysisStatusValue
  progress: number
  stage: string | null
  createdAt: string
}

// ---------------- 健康报告（06 §3.7） ----------------

export interface ReportDimension {
  key: 'quality' | 'structure' | 'dependency' | 'scale'
  score: number
  stars: number
  summary: string
}

export interface ReportRisk {
  level: 'HIGH' | 'MEDIUM' | 'LOW'
  title: string
  detail: string
  suggestion: string
  references: { file: string; line?: number }[]
}

export interface ReportRecommendation {
  phase: string
  items: string[]
}

export interface HealthReport {
  healthScore: number
  level: 'EXCELLENT' | 'GOOD' | 'FAIR' | 'POOR'
  summary: string
  techStack: { languages: Record<string, number>; frameworks: string[] }
  dimensions: ReportDimension[]
  risks: ReportRisk[]
  recommendations: ReportRecommendation[]
  scoreDetail?: Record<string, number>
}

export interface ReportDetail {
  analysisId: number
  generatedAt: string
  source: 'LLM' | 'RULES'
  promptVersion: string
  report: HealthReport
}

// ---------------- 质量 issues（06 §3.10） ----------------

export interface QualityMetrics {
  bugs: number
  vulnerabilities: number
  codeSmells: number
  duplicationRate: number | null
  coverageRate: number | null
  complexity: number | null
  available: boolean
  comparedWithLast: Record<string, number> | null
}

export interface QualityIssueItem {
  id: number
  severity: 'BLOCKER' | 'CRITICAL' | 'MAJOR' | 'MINOR' | 'INFO'
  kind: 'BUG' | 'VULNERABILITY' | 'SMELL'
  ruleKey: string | null
  filePath: string | null
  line: number | null
  message: string | null
  aiExplanation: string | null
  aiSuggestion: string | null
  aiStatus: 'PENDING' | 'DONE' | 'FAILED'
  status: 'OPEN' | 'IGNORED' | 'FIXED'
}

export interface QualityIssuesResult {
  metrics: QualityMetrics
  total: number
  items: QualityIssueItem[]
}

// ---------------- 架构（06 §3.11，P4c）----------------

export type ArchitectureNodeType =
  'CONTROLLER' | 'SERVICE' | 'REPOSITORY' | 'ENTITY' | 'UTIL' | 'OTHER'

export interface ArchitectureNode {
  id: number
  nodeKey: string
  name: string
  nodeType: ArchitectureNodeType
  filePath: string
  metrics: { outDegree: number; inDegree: number }
}

export interface ArchitectureEdge {
  id: number
  sourceNodeId: number
  targetNodeId: number
  relation: 'CALL' | 'INHERIT' | 'COMPOSE' | string
}

export interface ArchitectureViolation {
  id: number
  violationType: string
  description: string
  sourceNodeId: number | null
  targetNodeId: number | null
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  suggestion: string
  aiNote: string | null
}

export interface ArchitectureResult {
  nodes: ArchitectureNode[]
  edges: ArchitectureEdge[]
  violations: ArchitectureViolation[]
}

// ---------- P5 演化（06 §3.13） ----------

export interface EvolutionCommit {
  hash: string
  authorName: string | null
  authorEmail: string | null
  committedAt: string
  linesAdded: number
  linesRemoved: number
  filesChanged: number
  message: string
}

export interface EvolutionTrend {
  week: string
  commits: number
  linesAdded: number
  linesRemoved: number
}

export interface EvolutionTopFile {
  filePath: string
  commitCount: number
  linesAdded: number
  linesRemoved: number
}

export interface EvolutionAuthor {
  authorName: string
  commits: number
  linesAdded: number
}

export interface EvolutionHotspot {
  module: string
  riskLevel: 'HIGH' | 'MEDIUM'
  evidence: string[]
  aiConclusion: string | null
}

export interface EvolutionResult {
  available: boolean
  commits: EvolutionCommit[]
  trend: EvolutionTrend[]
  topFiles: EvolutionTopFile[]
  authors: EvolutionAuthor[]
  hotspots: EvolutionHotspot[]
}

// ---------- P6 AI 医生（06 §3.15 / §4） ----------

export interface ChatSessionItem {
  id: number
  title: string
  messageCount: number
  createdAt: string
  lastMessageAt: string | null
}

export interface ChatCitation {
  file: string
  line: number
  excerpt: string
}

export interface ChatMessageItem {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  citations: ChatCitation[] | null
  createdAt: string
}

export interface ChatSessionsResult {
  total: number
  items: ChatSessionItem[]
}

// ---------- P7 技术债（06 §3.12） ----------

export type TechDebtSource = 'ARCH' | 'QUALITY' | 'DEPEND' | 'EVOLUTION' | 'AI_DOCTOR' | 'MANUAL'
export type TechDebtStatus = 'OPEN' | 'DOING' | 'DONE' | 'WONTFIX'

export interface TechDebtItem {
  id: number
  source: TechDebtSource
  title: string
  level: 'HIGH' | 'MEDIUM' | 'LOW'
  description: string | null
  suggestion: string | null
  status: TechDebtStatus
  refAnalysisId: number | null
  createdAt: string
  resolvedAt: string | null
}

// ---------- P7b 文档（06 §3.14） ----------

export type DocType = 'README' | 'ARCH' | 'API'

export interface DocItem {
  id: number
  docType: DocType
  title: string
  content: string
  version: number
  edited: boolean
  createdAt: string
}
