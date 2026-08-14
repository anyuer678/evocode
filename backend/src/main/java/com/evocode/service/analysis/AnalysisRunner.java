package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.dto.scan.ScanFileResp;
import com.evocode.dto.scan.ScanResultResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.entity.QualityIssue;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.enums.Stage;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import com.evocode.service.ArchitectureService;
import com.evocode.service.EvolutionService;
import com.evocode.service.debt.TechDebtService;
import com.evocode.service.dependency.DependencyService;
import com.evocode.service.report.ReportStorageService;
import com.evocode.service.scan.FileNodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析任务异步执行体（docs/06-API契约.md §3.6 状态机）。
 *
 * <p>进度流转：PENDING(QUEUED,0) → RUNNING(SCAN,5→70) → SCAN_DONE(70)
 * → RUNNING(REPORT,75→95) → SUCCEEDED(DONE,100)。REPORT 阶段调 analyzer
 * /analyze/v1/report（LLM 优先，analyzer 内部降级 RULES）。
 *
 * <p>独立 bean 而非 Service 内部方法：保证 @Async 经 Spring 代理生效
 * （同 bean 自调用不会走代理）。
 */
@Slf4j
@Service
public class AnalysisRunner {

    private final AnalysisMapper analysisMapper;
    private final ProjectMapper projectMapper;
    private final AnalyzerClient analyzerClient;
    private final FileNodeService fileNodeService;
    private final QualityIssueMapper qualityIssueMapper;
    private final ArchitectureService architectureService;
    private final EvolutionService evolutionService;
    private final DependencyService dependencyService;
    private final TechDebtService techDebtService;
    private final AnalysisProgressPublisher progressPublisher;
    private final ReportStorageService reportStorageService;

    public AnalysisRunner(AnalysisMapper analysisMapper, ProjectMapper projectMapper,
                          AnalyzerClient analyzerClient, FileNodeService fileNodeService,
                          QualityIssueMapper qualityIssueMapper, ArchitectureService architectureService,
                          EvolutionService evolutionService, DependencyService dependencyService,
                          TechDebtService techDebtService, AnalysisProgressPublisher progressPublisher,
                          ReportStorageService reportStorageService) {
        this.analysisMapper = analysisMapper;
        this.projectMapper = projectMapper;
        this.analyzerClient = analyzerClient;
        this.fileNodeService = fileNodeService;
        this.qualityIssueMapper = qualityIssueMapper;
        this.architectureService = architectureService;
        this.evolutionService = evolutionService;
        this.dependencyService = dependencyService;
        this.techDebtService = techDebtService;
        this.progressPublisher = progressPublisher;
        this.reportStorageService = reportStorageService;
    }

    @Async("quickScanExecutor")
    public void run(Long analysisId) {
        Analysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            log.warn("分析任务不存在，跳过 analysisId={}", analysisId);
            return;
        }
        Project project = projectMapper.selectById(analysis.getProjectId());
        if (project == null) {
            fail(analysis, "项目已被删除");
            return;
        }

        analysis.setStatus(AnalysisStatus.RUNNING.name());
        analysis.setStage(Stage.SCAN.name());
        analysis.setProgress(5);
        analysis.setStartedAt(OffsetDateTime.now());
        analysisMapper.updateById(analysis);
        publishProgress(project, analysis, "RUNNING", Stage.SCAN.name(), 5, "开始扫描…");

        project.setStatus(ProjectStatus.ANALYZING.name());
        projectMapper.updateById(project);

        try {
            // 传绝对路径：analyzer 与 backend 可能不在同一 cwd
            String codeDir = Path.of(project.getStoragePath()).toAbsolutePath().toString();
            ScanResultResp scan = analyzerClient.scan(project.getId(), codeDir);

            Analysis current = analysisMapper.selectById(analysis.getId());
            if (current != null && AnalysisStatus.CANCELLED.name().equals(current.getStatus())) {
                log.info("分析被取消 analysisId={}", analysis.getId());
                return;
            }

            fileNodeService.replaceSnapshot(project.getId(), analysis.getId(), scan.files());
            updateArchive(project, scan);

            analysis.setStage(Stage.SCAN_DONE.name());
            analysis.setProgress(70);
            analysisMapper.updateById(analysis);
            publishProgress(project, analysis, "RUNNING", Stage.SCAN_DONE.name(), 70, "扫描完成，开始深度分析…");

            Map<String, Object> qualityMetrics = runQuality(project, analysis, codeDir);
            // 审查：REPORT 阶段各子阶段前检查取消（删除项目/取消任务后不再写入孤儿数据）
            if (!isCancelled(analysis.getId())) {
                runArchitecture(project, analysis, codeDir);
            }
            if (!isCancelled(analysis.getId())) {
                runEvolution(project, analysis, codeDir);
            }
            if (!isCancelled(analysis.getId())) {
                runDependency(project, analysis, codeDir);
            }
            if (!isCancelled(analysis.getId())) {
                generateAndStoreReport(analysis, project, scan, qualityMetrics);
            }
        } catch (Exception e) {
            log.error("分析失败 analysisId={} projectId={}", analysis.getId(), project.getId(), e);
            fail(analysis, e.getMessage());
            project.setStatus(ProjectStatus.FAILED.name());
            projectMapper.updateById(project);
        }
    }

    /**
     * 重新生成报告（06 §3.7 regenerate）：不重扫，用库内已存快照重建 scan 摘要。
     * 前置校验（2001/2008）由 AnalysisService 完成。
     */
    @Async("quickScanExecutor")
    public void regenerateReport(Long analysisId) {
        Analysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            log.warn("分析任务不存在，跳过 regenerate analysisId={}", analysisId);
            return;
        }
        Project project = projectMapper.selectById(analysis.getProjectId());
        if (project == null) {
            fail(analysis, "项目已被删除");
            return;
        }

        analysis.setStatus(AnalysisStatus.RUNNING.name());
        analysis.setStage(Stage.REPORT.name());
        analysis.setProgress(75);
        analysisMapper.updateById(analysis);

        try {
            List<ScanFileResp> files = fileNodeService.listAllForReport(project.getId());
            ScanResultResp scan = rebuildScan(project, files);
            // regenerate 不重扫：沿用库内快照，质量指标不再重跑（report 走代理或留空）
            generateAndStoreReport(analysis, project, scan, null);
            analysis.setRegeneratedAt(OffsetDateTime.now());
            analysisMapper.updateById(analysis);
        } catch (Exception e) {
            log.error("重新生成报告失败 analysisId={}", analysis.getId(), e);
            fail(analysis, e.getMessage());
        }
    }

    /** REPORT 阶段：质量分析（issues 落库）→ 调 /analyze/report（携带质量指标）→ 回填报告字段。 */
    private void generateAndStoreReport(Analysis analysis, Project project, ScanResultResp scan,
                                        Map<String, Object> qualityMetrics) {
        analysis.setStage(Stage.REPORT.name());
        analysis.setProgress(75);
        analysisMapper.updateById(analysis);
        publishProgress(project, analysis, "RUNNING", Stage.REPORT.name(), 75, "生成健康报告…");

        AnalyzerClient.ReportResp report = analyzerClient.report(
                project.getId(), scan, qualityMetrics, historySummaries(project.getId(), analysis.getId()));
        reportStorageService.saveReport(analysis.getId(), report.report());
        analysis.setReportSource(report.source());
        analysis.setPromptVersion(report.promptVersion());

        // P7a：分析产物聚合生成技术债（同 analysis 幂等重建；失败不阻塞分析完成）
        try {
            techDebtService.rebuildForAnalysis(project.getId(), analysis.getId(), report.report());
        } catch (Exception e) {
            log.warn("技术债聚合失败 analysisId={}：{}", analysis.getId(), e.getMessage());
        }

        analysis.setStatus(AnalysisStatus.SUCCEEDED.name());
        analysis.setStage(Stage.DONE.name());
        analysis.setProgress(100);
        analysis.setFinishedAt(OffsetDateTime.now());
        analysisMapper.updateById(analysis);
        publishProgress(project, analysis, "SUCCEEDED", Stage.DONE.name(), 100, "分析完成");
        log.info("分析完成 analysisId={} projectId={} source={}",
                analysis.getId(), project.getId(), report.source());
    }

    /**
     * 质量分析（06 §5.3）：Sonar 可用时把 issues 落库并返回 metrics 供报告使用；
     * 不可用返回 null（报告质量维度走代理指标）。
     */
    private Map<String, Object> runQuality(Project project, Analysis analysis, String codeDir) {
        AnalyzerClient.QualityResp quality;
        try {
            quality = analyzerClient.quality(project.getId(), codeDir);
        } catch (Exception e) {
            log.warn("质量分析不可用 projectId={}：{}", project.getId(), e.getMessage());
            return null;
        }
        // 审查 P0：Sonar 不可用时 analyzer 仍返回 8 类规则扫描 issues（available=false + issues），
        // 必须落库——否则默认环境（无 Sonar）质量分析/逐行标注永远为空
        boolean sonarAvailable = quality != null && quality.metrics() != null
                && Boolean.TRUE.equals(quality.metrics().get("available"));
        if (quality != null && quality.issues() != null && !quality.issues().isEmpty()) {
            try {
                // 审查：issues 落库带 analysisId（V002 NOT NULL），失败降级不阻塞报告
                replaceQualityIssues(project.getId(), analysis.getId(), quality.issues());
            } catch (Exception e) {
                log.warn("质量 issues 落库失败，降级跳过 projectId={}：{}", project.getId(), e.getMessage());
            }
        }
        if (!sonarAvailable) {
            return null;
        }
        return quality.metrics();
    }

    /** 取消检查：任务被取消/项目被删除后停止后续阶段写入。 */
    private boolean isCancelled(Long analysisId) {
        Analysis current = analysisMapper.selectById(analysisId);
        return current != null && AnalysisStatus.CANCELLED.name().equals(current.getStatus());
    }

    /**
     * 架构分析（06 §5.5）：算法解析，结果以 analysisId 为单位落库。失败只记录，不阻塞
     * 报告生成（可承受降级）。
     */
    private void runArchitecture(Project project, Analysis analysis, String codeDir) {
        try {
            var arch = analyzerClient.architecture(project.getId(), codeDir);
            architectureService.replaceForAnalysis(project.getId(), analysis.getId(), arch);
            log.info("架构分析落库完成 analysisId={} nodes={}",
                    analysis.getId(), arch == null ? 0 : arch.nodes().size());
        } catch (Exception e) {
            log.warn("架构分析不可用，跳过 projectId={}：{}", project.getId(), e.getMessage());
        }
    }

    /**
     * 演化统计（06 §5.6）：git log 聚合，结果以 analysisId 为单位落库。失败只记录，不阻塞
     * 报告生成（可承受降级）。落库窗口 365d，查询端点再按 range 过滤。
     */
    private void runEvolution(Project project, Analysis analysis, String codeDir) {
        try {
            var evolution = analyzerClient.evolution(project.getId(), codeDir, 365);
            evolutionService.replaceForAnalysis(project.getId(), analysis.getId(), evolution);
            log.info("演化统计落库完成 analysisId={} available={} commits={}",
                    analysis.getId(), evolution == null || evolution.available(),
                    evolution == null ? 0 : evolution.commits().size());
        } catch (Exception e) {
            log.warn("演化统计不可用，跳过 projectId={}：{}", project.getId(), e.getMessage());
        }
    }

    /**
     * 依赖分析（06 §5.10，P9d）：pom/package 解析 + EOL 判定，以 analysisId 落库。
     * 失败只记录，不阻塞报告生成（可承受降级）。
     */
    private void runDependency(Project project, Analysis analysis, String codeDir) {
        try {
            var deps = analyzerClient.dependency(project.getId(), codeDir);
            dependencyService.replaceForAnalysis(project.getId(), analysis.getId(), deps);
            log.info("依赖分析落库完成 analysisId={} available={} deps={}",
                    analysis.getId(), deps == null || deps.available(),
                    deps == null ? 0 : deps.dependencies().size());
        } catch (Exception e) {
            log.warn("依赖分析不可用，跳过 projectId={}：{}", project.getId(), e.getMessage());
        }
    }

    /** 快照重建：同 analysis_id 二次写入先删后插。 */
    private void replaceQualityIssues(Long projectId, Long analysisId, List<Map<String, Object>> issues) {
        qualityIssueMapper.delete(new LambdaQueryWrapper<QualityIssue>()
                .eq(QualityIssue::getProjectId, projectId));
        if (issues == null || issues.isEmpty()) {
            return;
        }
        issues.forEach(raw -> {
            QualityIssue issue = new QualityIssue();
            issue.setProjectId(projectId);
            issue.setAnalysisId(analysisId);
            issue.setSource(String.valueOf(raw.getOrDefault("source", "SONAR")));
            issue.setSeverity(String.valueOf(raw.getOrDefault("severity", "INFO")));
            issue.setKind(String.valueOf(raw.getOrDefault("kind", "SMELL")));
            issue.setRuleKey(String.valueOf(raw.getOrDefault("ruleKey", "")));
            issue.setFilePath(String.valueOf(raw.getOrDefault("filePath", "")));
            Object line = raw.get("line");
            issue.setLine(line instanceof Number n ? n.intValue() : null);
            issue.setMessage(String.valueOf(raw.getOrDefault("message", "")));
            // 审查 P1：扫描器/规则引擎的 suggestion（【影响】…【修复】…）必须落库 ai_suggestion，
            // 否则前端逐行标注/质量列表的建议恒空
            Object suggestion = raw.get("suggestion");
            if (suggestion != null && !String.valueOf(suggestion).isBlank()) {
                issue.setAiSuggestion(String.valueOf(suggestion));
            }
            issue.setAiStatus("PENDING");
            issue.setStatus("OPEN");
            qualityIssueMapper.insert(issue);
        });
    }

    /** 历史报告摘要（最近 3 条 SUCCEEDED 且含报告），供 LLM 参考上期健康分。 */
    private List<Map<String, Object>> historySummaries(Long projectId, Long excludeId) {
        List<Analysis> prev = analysisMapper.selectList(new QueryWrapper<Analysis>()
                .eq("project_id", projectId)
                .ne("id", excludeId)
                .eq("status", AnalysisStatus.SUCCEEDED.name())
                .orderByDesc("id")
                .last("LIMIT 3"));
        return prev.stream().map(a -> {
            Map<String, Object> sum = new HashMap<>();
            // 审查：SPI-6 拆表后 report_json 在 analysis_report（analysis 列仅存量），改经 ReportStorageService 读
            var row = reportStorageService.getByAnalysisId(a.getId());
            Map<String, Object> report = row == null ? null : row.getReportJson();
            sum.put("healthScore", report == null ? null : report.get("healthScore"));
            sum.put("summary", report == null ? null : report.get("summary"));
            return sum;
        }).toList();
    }

    /** regenerate 不重扫：由项目档案 + file_node 快照重建 scan 摘要（06 §5.2）。 */
    private ScanResultResp rebuildScan(Project project, List<ScanFileResp> files) {
        return new ScanResultResp(
                project.getLangStats() != null ? project.getLangStats() : Map.of(),
                project.getLocTotal() != null ? project.getLocTotal() : 0L,
                project.getFileCount() != null ? project.getFileCount() : 0,
                project.getIgnoredCount() != null ? project.getIgnoredCount() : 0,
                project.getFrameworkTags() != null ? project.getFrameworkTags() : List.of(),
                false, false, List.of(), // hasBackend/hasFrontend/dbHint 未持久化，regenerate 置默认
                files, 0, false);
    }

    private void fail(Analysis analysis, String message) {
        analysis.setStatus(AnalysisStatus.FAILED.name());
        analysis.setErrorMessage(message);
        analysis.setFinishedAt(OffsetDateTime.now());
        analysisMapper.updateById(analysis);
        Project project = projectMapper.selectById(analysis.getProjectId());
        if (project != null) {
            publishProgress(project, analysis, "FAILED", analysis.getStage(),
                    analysis.getProgress() == null ? 0 : analysis.getProgress(),
                    "分析失败：" + (message == null ? "未知错误" : message));
        }
    }

    /** 进度广播（有订阅者才推送）。 */
    private void publishProgress(Project project, Analysis analysis, String status, String stage,
                                 int progress, String message) {
        progressPublisher.publish(project.getId(), analysis.getId(), status, stage, progress, message);
    }

    /** 扫描成功后回填项目档案（与 QuickScanService 语义一致）。 */
    private void updateArchive(Project project, ScanResultResp scan) {
        project.setLangStats(scan.languages());
        project.setFrameworkTags(scan.frameworks());
        project.setLocTotal(scan.locTotal());
        project.setFileCount(scan.fileCount());
        project.setIgnoredCount(scan.ignoredCount());
        project.setStatus(ProjectStatus.READY.name());
        projectMapper.updateById(project);
    }
}
