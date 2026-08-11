"""扫描契约（06 §5.1，唯一事实来源，backend 按此反序列化）。"""

from pydantic import BaseModel


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
