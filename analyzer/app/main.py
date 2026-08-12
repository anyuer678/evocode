"""EvoCode Analyzer 入口：仅监听 127.0.0.1（部署铁律）。"""

import json
import logging
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse

from .config import get_settings
from .core.arch.archscan import architecture_scan
from .core.dependency.depscan import scan_dependencies
from .core.docgen import generate_doc
from .core.evolution import evolution_scan
from .core.filescanner import scan_project
from .core.llm import OpenAICompatClient
from .core.prompts import REPORT_PROMPT_VERSION
from .core.rag.service import RagService
from .core.rag.vectorstore import KnowledgeStore
from .core.reportgen import generate_report
from .core.sonar import quality_scan
from .schemas import (
    ArchEdge,
    ArchNode,
    ArchRequest,
    ArchResult,
    ArchViolation,
    ChatRequest,
    DependencyItem,
    DependencyRequest,
    DependencyResult,
    DocRequest,
    DocResponse,
    EvolutionRequest,
    EvolutionResult,
    QualityIssue,
    QualityMetrics,
    QualityRequest,
    QualityResult,
    RagIndexRequest,
    RagIndexResponse,
    RagSearchRequest,
    RagSearchResponse,
    ReportRequest,
    ReportResponse,
    ScanRequest,
    ScanResult,
)
from .schemas import ScanFile as ScanFileOut

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)

settings = get_settings()
logger = logging.getLogger("evocode.analyzer")

app = FastAPI(title="EvoCode Analyzer", version=settings.version)

# AD-9：无 Key 时 available()=False，report 自动走规则版
_llm = OpenAICompatClient(
    base_url=settings.llm_base_url,
    api_key=settings.llm_api_key,
    model=settings.llm_model,
    timeout_seconds=settings.llm_timeout_seconds,
    max_retries=settings.llm_max_retries,
    embedding_model=settings.llm_embedding_model,
)

# P6 RAG：直连 PG（AD-P6-2），只读写 knowledge_chunk；DSN 未配 → store.available()=False
_rag_store = KnowledgeStore(settings.pg_dsn)
_rag = RagService(settings, _rag_store, _llm)


@app.get("/")
def root() -> dict:
    """根路径提示：本服务仅对内提供 API，页面入口在 frontend :5173。"""
    return {
        "service": "evocode-analyzer",
        "version": settings.version,
        "message": "这是 analyzer 内部 API 服务，前端页面请访问 http://localhost:5173",
        "endpoints": [
            "/health", "/analyze/v1/scan", "/analyze/v1/quality",
            "/analyze/v1/architecture", "/analyze/v1/report",
        ],
    }


@app.get("/health")
def health() -> dict:
    """健康检查（smoke.ps1 依赖）。"""
    return {
        "status": "ok",
        "service": settings.service_name,
        "version": settings.version,
    }


@app.post("/analyze/v1/scan")
def scan(req: ScanRequest) -> ScanResult:
    """扫描项目目录；产物：JSON 结果 + {projectId}.status.json（backend 轮询用）。"""
    code_dir = Path(req.codeDir)
    if not code_dir.is_dir():
        raise HTTPException(status_code=404, detail="codeDir not found")
    try:
        scanned = scan_project(code_dir)
    except OSError as exc:
        logger.error("scan failed project=%s: %s", req.projectId, exc)
        raise HTTPException(status_code=500, detail="scan failed") from exc

    result = ScanResult(
        languages=scanned.languages,
        locTotal=scanned.loc_total,
        fileCount=scanned.file_count,
        ignoredCount=scanned.ignored_count,
        frameworks=scanned.frameworks,
        hasBackend=scanned.has_backend,
        hasFrontend=scanned.has_frontend,
        dbHint=scanned.db_hint,
        files=[
            ScanFileOut(
                path=f.path, language=f.language, loc=f.loc, sizeBytes=f.sizeBytes
            )
            for f in scanned.files
        ],
        skippedBigFiles=scanned.skipped_big_files,
        truncated=scanned.truncated,
    )

    state_dir = Path(settings.status_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    status_file = state_dir / f"{req.projectId}.status.json"
    payload = {
        "projectId": req.projectId,
        "status": "READY",
        "result": result.model_dump(mode="json"),
    }
    try:
        status_file.write_text(
            json.dumps(payload, ensure_ascii=False),
            encoding="utf-8",
        )
    except OSError as exc:
        logger.error("write status file failed project=%s: %s", req.projectId, exc)
        raise HTTPException(status_code=500, detail="write status failed") from exc
    logger.info(
        "scan done project=%s files=%s loc=%s",
        req.projectId,
        result.fileCount,
        result.locTotal,
    )
    return result


@app.post("/analyze/v1/report")
def report(req: ReportRequest) -> ReportResponse:
    """生成健康报告（06 §5.2）。

    LLM 无 Key/失败 → 规则版降级（source=RULES，HTTP 仍 200）。
    """
    report_data, source, _ = generate_report(
        req.scan,
        history_reports=req.historyReports,
        llm=_llm,
        quality=req.quality,
    )
    logger.info("report done project=%s source=%s", req.projectId, source)
    return ReportResponse(
        source=source,
        promptVersion=REPORT_PROMPT_VERSION,
        report=report_data,
    )


@app.post("/analyze/v1/quality")
def quality(req: QualityRequest) -> QualityResult:
    """质量分析（06 §5.3）。

    Sonar 不可达/未配置 → 200 + metrics.available=false（非错误）。
    """
    code_dir = Path(req.codeDir)
    if not code_dir.is_dir():
        raise HTTPException(status_code=404, detail="codeDir not found")
    result = quality_scan(req.projectId, str(code_dir), settings)
    if result is None:
        logger.info("quality N/A project=%s", req.projectId)
        return QualityResult(metrics=QualityMetrics(available=False))
    logger.info(
        "quality done project=%s bugs=%s smells=%s issues=%s",
        req.projectId,
        result["metrics"].get("bugs"),
        result["metrics"].get("codeSmells"),
        len(result["issues"]),
    )
    return QualityResult(
        metrics=QualityMetrics(**result["metrics"]),
        issues=[QualityIssue(**i) for i in result["issues"]],
    )


@app.post("/analyze/v1/architecture")
def architecture(req: ArchRequest) -> ArchResult:
    """架构分析（06 §5.5）：tree-sitter 提取节点/调用边 + 分层违规。"""
    code_dir = Path(req.codeDir)
    if not code_dir.is_dir():
        raise HTTPException(status_code=404, detail="codeDir not found")
    data = architecture_scan(str(code_dir), req.languages)
    logger.info(
        "architecture done project=%s nodes=%s edges=%s violations=%s",
        req.projectId,
        len(data["nodes"]),
        len(data["edges"]),
        len(data["violations"]),
    )
    return ArchResult(
        nodes=[ArchNode(**n) for n in data["nodes"]],
        edges=[ArchEdge(**e) for e in data["edges"]],
        violations=[ArchViolation(**v) for v in data["violations"]],
    )


@app.post("/analyze/v1/evolution")
def evolution(req: EvolutionRequest) -> EvolutionResult:
    """演化统计（06 §5.6）：git log 聚合 + 规则热点；非 git → available=false。"""
    git_dir = Path(req.gitDir)
    if not git_dir.is_dir():
        raise HTTPException(status_code=404, detail="gitDir not found")
    data = evolution_scan(str(git_dir), req.rangeDays)
    logger.info(
        "evolution done project=%s available=%s commits=%s hotspots=%s",
        req.projectId,
        data["available"],
        len(data["commits"]),
        len(data["hotspots"]),
    )
    return EvolutionResult(**data)


@app.post("/analyze/v1/dependency")
def dependency(req: DependencyRequest) -> DependencyResult:
    """依赖清单分析（06 §5.10，P9d）：pom.xml / package.json 解析 + EOL 判定。

    无 Maven/npm 依赖文件 → 200 + available=false（非错误）。
    """
    code_dir = Path(req.codeDir)
    if not code_dir.is_dir():
        raise HTTPException(status_code=404, detail="codeDir not found")
    data = scan_dependencies(str(code_dir))
    logger.info(
        "dependency done project=%s available=%s deps=%s",
        req.projectId,
        data["available"],
        len(data["dependencies"]),
    )
    return DependencyResult(
        available=data["available"],
        dependencies=[DependencyItem(**d) for d in data["dependencies"]],
    )


@app.post("/analyze/v1/rag/index")
def rag_index(req: RagIndexRequest) -> RagIndexResponse:
    """RAG 知识索引（06 §5.8）：切片 + embedding（失败降级 NULL）+ 入库。"""
    code_dir = Path(req.codeDir)
    if not code_dir.is_dir():
        raise HTTPException(status_code=404, detail="codeDir not found")
    result = _rag.index(req.projectId, str(code_dir), req.languages, req.analysisId)
    logger.info(
        "rag index project=%s chunks=%s model=%s stored=%s",
        req.projectId,
        result["chunks"],
        result["embeddingModel"],
        result["stored"],
    )
    return RagIndexResponse(**result)


@app.post("/analyze/v1/rag/search")
def rag_search(req: RagSearchRequest) -> RagSearchResponse:
    """RAG 检索（06 §5.8）：向量 cosine + 关键词合并去重。"""
    try:
        chunks = _rag.search(req.projectId, req.query, req.topK)
    except Exception as exc:  # PG 未配置或宕机（psycopg.Error）→ 503
        logger.warning("rag search unavailable: %s", exc)
        raise HTTPException(
            status_code=503,
            detail={"error": {"code": "RAG_UNAVAILABLE", "message": str(exc)}},
        ) from exc
    logger.info("rag search project=%s hits=%s", req.projectId, len(chunks))
    return RagSearchResponse(chunks=chunks)


@app.post("/analyze/v1/chat")
def analyze_chat(req: ChatRequest) -> StreamingResponse:
    """AI 医生 SSE 生成端（06 §5.7 / §4）：delta/citations/done/error。"""

    def gen():
        for event in _rag.chat(
            req.projectId,
            req.query,
            req.systemContext or {},
            [h.model_dump() for h in req.history],
            req.fileRef.model_dump() if req.fileRef else None,
        ):
            payload = json.dumps(event["data"], ensure_ascii=False)
            yield f"event: {event['event']}\ndata: {payload}\n\n"

    logger.info("chat start project=%s query=%s", req.projectId, req.query[:40])
    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/analyze/v1/doc")
def analyze_doc(req: DocRequest) -> DocResponse:
    """文档生成（06 §5.9 契约新增，P7b）：README/ARCH/API 三类（D.7 prompt）。"""
    if req.docType.upper() not in ("README", "ARCH", "API"):
        raise HTTPException(status_code=400, detail="docType 必须是 README/ARCH/API")
    try:
        result = generate_doc(
            _llm,
            req.docType,
            scan=req.scan,
            arch=req.arch,
            project_info=req.projectInfo or {},
            code_dir=req.codeDir,
        )
    except Exception as exc:
        code = "LLM_NO_KEY" if "LLM_NO_KEY" in str(exc) else "LLM_FAILED"
        logger.warning("doc 生成失败 %s project=%s：%s", code, req.projectId, exc)
        raise HTTPException(
            status_code=400 if code == "LLM_NO_KEY" else 502,
            detail={"error": {"code": code, "message": f"文档生成失败：{exc}"}},
        ) from exc
    logger.info("doc done project=%s type=%s title=%s", req.projectId,
                result["docType"], result["title"][:40])
    return DocResponse(**result)
