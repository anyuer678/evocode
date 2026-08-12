"""扫描契约（06 §5.1，唯一事实来源，backend 按此反序列化）。"""

from pydantic import BaseModel, Field


class ScanRequest(BaseModel):
    projectId: int
    codeDir: str


class ScanFile(BaseModel):
    path: str
    language: str
    loc: int
    sizeBytes: int


class ScanResult(BaseModel):
    languages: dict[str, float]
    locTotal: int
    fileCount: int
    ignoredCount: int
    frameworks: list[str]
    hasBackend: bool
    hasFrontend: bool
    dbHint: list[str]
    files: list[ScanFile]
    skippedBigFiles: int
    truncated: bool = False


class ReportRequest(BaseModel):
    """报告请求（06 §5.2）。quality/arch/evolution 为预留字段，v0.1 传 null。"""

    projectId: int
    scan: ScanResult
    quality: dict | None = None
    arch: dict | None = None
    evolution: dict | None = None
    historyReports: list[dict] = []
    regenerate: bool = False


class Dimension(BaseModel):
    key: str
    score: int
    stars: int
    summary: str


class Risk(BaseModel):
    level: str
    title: str
    detail: str
    suggestion: str
    references: list[dict] = []


class Recommendation(BaseModel):
    phase: str
    items: list[str]


class HealthReport(BaseModel):
    healthScore: int
    level: str
    summary: str
    techStack: dict
    dimensions: list[Dimension]
    risks: list[Risk]
    recommendations: list[Recommendation]
    scoreDetail: dict | None = None


class ReportResponse(BaseModel):
    source: str
    promptVersion: str
    report: HealthReport


class QualityMetrics(BaseModel):
    """质量指标（06 §3.10）。available=false 表示 Sonar 未启用，其余字段可空。"""

    bugs: int | None = None
    vulnerabilities: int | None = None
    codeSmells: int | None = None
    duplicationRate: float | None = None
    coverageRate: float | None = None
    complexity: float | None = None
    available: bool


class QualityIssue(BaseModel):
    ruleKey: str
    severity: str
    kind: str
    filePath: str
    line: int | None = None
    message: str


class QualityRequest(BaseModel):
    """质量分析请求（06 §5.3）。"""

    projectId: int
    codeDir: str


class QualityResult(BaseModel):
    metrics: QualityMetrics
    issues: list[QualityIssue] = []


# ---- P9e TD-01：issue 解释（06 §5.4） ----


class ExplainRequest(BaseModel):
    """质量 issue 解释请求（06 §5.4）。"""

    issue: QualityIssue
    fileSnippet: str | None = None


class ExplainResponse(BaseModel):
    explanation: str
    suggestion: str
    codeExample: str = ""
    source: str = "RULES"  # RULES（规则版）/ LLM（增强）


class ArchRequest(BaseModel):
    """架构分析请求（06 §5.5）。"""

    projectId: int
    codeDir: str
    languages: list[str] = []


class ArchNode(BaseModel):
    nodeKey: str
    name: str
    nodeType: str
    filePath: str
    metrics: dict = {}


class ArchEdge(BaseModel):
    sourceNodeKey: str
    targetNodeKey: str
    relation: str = "CALL"


class ArchViolation(BaseModel):
    violationType: str
    description: str
    sourceNodeKey: str | None = None
    targetNodeKey: str | None = None
    severity: str
    suggestion: str


class ArchResult(BaseModel):
    nodes: list[ArchNode] = []
    edges: list[ArchEdge] = []
    violations: list[ArchViolation] = []


# ---------- P5 演化（06 §5.6） ----------


class EvolutionRequest(BaseModel):
    """演化统计请求（06 §5.6）。"""

    projectId: int
    gitDir: str
    rangeDays: int = Field(default=30, ge=1, le=3650)


class EvolutionCommit(BaseModel):
    hash: str
    authorName: str | None = None
    authorEmail: str | None = None
    committedAt: str
    linesAdded: int = 0
    linesRemoved: int = 0
    filesChanged: int = 0
    message: str = ""


class EvolutionTrend(BaseModel):
    week: str
    commits: int = 0
    linesAdded: int = 0
    linesRemoved: int = 0


class EvolutionTopFile(BaseModel):
    filePath: str
    commitCount: int = 0
    linesAdded: int = 0
    linesRemoved: int = 0


class EvolutionAuthor(BaseModel):
    authorName: str
    commits: int = 0
    linesAdded: int = 0


class EvolutionHotspot(BaseModel):
    module: str
    riskLevel: str  # HIGH / MEDIUM
    evidence: list[str] = []


class EvolutionResult(BaseModel):
    available: bool = True
    commits: list[EvolutionCommit] = []
    trend: list[EvolutionTrend] = []
    topFiles: list[EvolutionTopFile] = []
    authors: list[EvolutionAuthor] = []
    hotspots: list[EvolutionHotspot] = []


# ---- P9d 依赖分析（06 §5.10） ----


class DependencyRequest(BaseModel):
    """依赖清单请求（P9d D1）。"""

    projectId: int
    codeDir: str


class DependencyItem(BaseModel):
    """单个依赖（07 §3.4 dependency 表对应字段）。"""

    name: str
    version: str | None = None
    type: str = "MAVEN"  # MAVEN / NPM
    file: str | None = None
    risk: str | None = None  # HIGH / MEDIUM / None（未命中规则=未知，不误报）
    reason: str | None = None
    latest: str | None = None
    isEol: bool = False


class DependencyResult(BaseModel):
    available: bool = False
    dependencies: list[DependencyItem] = []


# ---- P6 RAG（06 §5.8）----


class RagIndexRequest(BaseModel):
    projectId: int
    codeDir: str
    languages: list[str] | None = None  # 默认 ["python", "java"]
    analysisId: int | None = None  # backend 分析链路传入；手动索引可为空


class RagIndexResponse(BaseModel):
    chunks: int
    embeddingModel: str | None
    stored: bool
    message: str | None = None


class RagSearchRequest(BaseModel):
    projectId: int
    query: str = Field(min_length=1, max_length=500)
    topK: int = Field(default=8, ge=1, le=20)


class RagSearchChunk(BaseModel):
    file: str
    chunkIndex: int
    content: str
    meta: dict = {}
    score: float


class RagSearchResponse(BaseModel):
    chunks: list[RagSearchChunk] = []


# ---- P6 AI 医生（06 §5.7 / §4 SSE 协议）----


class ChatHistoryItem(BaseModel):
    role: str  # user / assistant
    content: str


class ChatFileRef(BaseModel):
    path: str
    content: str = ""


class ChatRequest(BaseModel):
    projectId: int
    systemContext: dict = {}
    history: list[ChatHistoryItem] = []  # 已截断：≤6 轮 + 摘要（backend 负责）
    query: str = Field(min_length=1, max_length=2000)
    fileRef: ChatFileRef | None = None


# ---- P7b 文档生成（06 §5.9 契约新增）----


class DocRequest(BaseModel):
    projectId: int
    docType: str  # README / ARCH / API
    scan: dict | None = None
    arch: dict | None = None
    projectInfo: dict = {}
    codeDir: str | None = None  # API 文档：analyzer 直读磁盘扫描 controller


class DocResponse(BaseModel):
    docType: str
    title: str
    content: str
    source: str = "RULES"  # LLM / RULES（TD-08：无 Key 规则版降级）
