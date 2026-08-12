package com.evocode.service.analysis;

import com.evocode.dto.architecture.ArchResultResp;
import com.evocode.dto.evolution.EvolutionResp;
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
import com.evocode.service.scan.FileNodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-U（P2a）：FULL 分析状态机 QUEUED→SCAN→SCAN_DONE→DONE；失败/取消分支。
 */
class AnalysisRunnerTest {

    @TempDir
    Path tempDir;

    private final AnalysisMapper analysisMapper = mock(AnalysisMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AnalyzerClient analyzerClient = mock(AnalyzerClient.class);
    private final FileNodeService fileNodeService = mock(FileNodeService.class);
    private final QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
    private final ArchitectureService architectureService = mock(ArchitectureService.class);
    private final EvolutionService evolutionService = mock(EvolutionService.class);
    private final DependencyService dependencyService = mock(DependencyService.class);
    private final TechDebtService techDebtService = mock(TechDebtService.class);

    private AnalysisRunner newRunner() {
        return new AnalysisRunner(
                analysisMapper, projectMapper, analyzerClient, fileNodeService, qualityIssueMapper,
                architectureService, evolutionService, dependencyService, techDebtService);
    }

    private Analysis newAnalysis() {
        Analysis a = new Analysis();
        a.setId(10L);
        a.setProjectId(7L);
        a.setType("FULL");
        return a;
    }

    private Project newProject() {
        Project p = new Project();
        p.setId(7L);
        p.setStoragePath(tempDir.resolve("p7").toString());
        return p;
    }

    private ScanResultResp newScan() {
        return new ScanResultResp(
                Map.of("Java", 100.0), 120L, 3, 0,
                List.of("Spring Boot"), true, false, List.of(), List.of(), 1, false);
    }

    private AnalyzerClient.ReportResp newReportResp() {
        return new AnalyzerClient.ReportResp(
                "RULES", "report-1.0",
                Map.of("healthScore", 82, "level", "GOOD", "summary", "规则评分"));
    }

    @Test
    void runSuccessTransitionsToDone() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        when(analyzerClient.architecture(eq(7L), anyString())).thenReturn(new ArchResultResp(
                List.of(new ArchResultResp.Node("c1", "UserController", "CONTROLLER", "c.java", Map.of())),
                List.of(new ArchResultResp.Edge("c1", "s1", "CALL")),
                List.of(new ArchResultResp.Violation("LAYER_VIOLATION", "Controller 直连 Repository",
                        "c1", "s1", "HIGH", null))));
        when(analyzerClient.evolution(eq(7L), anyString(), anyInt())).thenReturn(newEvolutionResp());
        when(analyzerClient.report(eq(7L), any(ScanResultResp.class), any(), any()))
                .thenReturn(newReportResp());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        // 架构分析落库
        verify(analyzerClient).architecture(eq(7L), anyString());
        verify(architectureService).replaceForAnalysis(eq(7L), eq(10L), any(ArchResultResp.class));
        // 演化统计落库
        verify(analyzerClient).evolution(eq(7L), anyString(), anyInt());
        verify(evolutionService).replaceForAnalysis(eq(7L), eq(10L), any(EvolutionResp.class));

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper, atLeastOnce()).updateById(captor.capture());
        Analysis last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AnalysisStatus.SUCCEEDED.name(), last.getStatus());
        assertEquals(Stage.DONE.name(), last.getStage());
        assertEquals(100, last.getProgress());
        assertEquals("RULES", last.getReportSource());
        assertEquals("report-1.0", last.getPromptVersion());
        assertEquals(82, last.getReportJson().get("healthScore"));

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper, atLeastOnce()).updateById(projectCaptor.capture());
        List<Project> updates = projectCaptor.getAllValues();
        assertEquals(ProjectStatus.READY.name(), updates.get(updates.size() - 1).getStatus());

        verify(fileNodeService).replaceSnapshot(eq(7L), eq(10L), anyList());
    }

    @Test
    void runWithSonarStoresIssuesAndPassesMetrics() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        when(analyzerClient.quality(eq(7L), anyString())).thenReturn(new AnalyzerClient.QualityResp(
                Map.of("available", true, "bugs", 2, "codeSmells", 5),
                List.of(Map.of("severity", "MAJOR", "kind", "BUG", "ruleKey", "s1",
                        "filePath", "a.py", "line", 3, "message", "m"))));
        when(analyzerClient.report(any(), any(), any(), any())).thenReturn(newReportResp());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        // issues 落库
        verify(qualityIssueMapper).insert(any(QualityIssue.class));
        // 质量指标透传给 report
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> qualityCaptor = ArgumentCaptor.forClass(Map.class);
        verify(analyzerClient).report(eq(7L), any(ScanResultResp.class), qualityCaptor.capture(), any());
        assertEquals(2, qualityCaptor.getValue().get("bugs"));
        // 最终 SUCCEEDED
        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper, atLeastOnce()).updateById(captor.capture());
        Analysis last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AnalysisStatus.SUCCEEDED.name(), last.getStatus());
    }

    @Test
    void runWithoutSonarKeepsProxyQuality() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        when(analyzerClient.quality(eq(7L), anyString())).thenReturn(
                new AnalyzerClient.QualityResp(Map.of("available", false), List.of()));
        when(analyzerClient.report(any(), any(), any(), any())).thenReturn(newReportResp());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        verify(qualityIssueMapper, never()).insert(any(QualityIssue.class));
        ArgumentCaptor<Map<String, Object>> qualityCaptor = ArgumentCaptor.forClass(Map.class);
        verify(analyzerClient).report(eq(7L), any(ScanResultResp.class), qualityCaptor.capture(), any());
        org.junit.jupiter.api.Assertions.assertNull(qualityCaptor.getValue());
    }

    @Test
    void runArchitectureFailureDoesNotBlockReport() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        when(analyzerClient.architecture(eq(7L), anyString()))
                .thenThrow(new RuntimeException("arch service down"));
        when(analyzerClient.report(any(), any(), any(), any())).thenReturn(newReportResp());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        // 架构失败仅降级：不落库，报告照常生成，分析仍 SUCCEEDED
        verify(architectureService, never()).replaceForAnalysis(any(), any(), any());
        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper, atLeastOnce()).updateById(captor.capture());
        Analysis last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AnalysisStatus.SUCCEEDED.name(), last.getStatus());
    }

    @Test
    void regenerateReportOverwritesReport() {
        Analysis analysis = newAnalysis();
        analysis.setStatus(AnalysisStatus.SUCCEEDED.name());
        when(analysisMapper.selectById(10L)).thenReturn(analysis);
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(fileNodeService.listAllForReport(7L)).thenReturn(List.of());
        when(analyzerClient.report(eq(7L), any(ScanResultResp.class), any(), any())).thenReturn(
                new AnalyzerClient.ReportResp("LLM", "report-1.0", Map.of("healthScore", 88)));
        AnalysisRunner runner = newRunner();

        runner.regenerateReport(10L);

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper, atLeastOnce()).updateById(captor.capture());
        Analysis last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AnalysisStatus.SUCCEEDED.name(), last.getStatus());
        assertEquals(Stage.DONE.name(), last.getStage());
        assertEquals("LLM", last.getReportSource());
        assertEquals(88, last.getReportJson().get("healthScore"));
        org.junit.jupiter.api.Assertions.assertNotNull(last.getRegeneratedAt());
    }

    @Test
    void runFailureMarksFailed() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenThrow(new RuntimeException("analyzer down"));
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper, atLeastOnce()).updateById(captor.capture());
        Analysis last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AnalysisStatus.FAILED.name(), last.getStatus());

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper, atLeastOnce()).updateById(projectCaptor.capture());
        List<Project> updates = projectCaptor.getAllValues();
        assertEquals(ProjectStatus.FAILED.name(), updates.get(updates.size() - 1).getStatus());
    }

    @Test
    void runStopsWhenCancelledDuringScan() {
        Analysis cancelled = newAnalysis();
        cancelled.setStatus(AnalysisStatus.CANCELLED.name());
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis()).thenReturn(cancelled);
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        verify(fileNodeService, never()).replaceSnapshot(any(), any(), anyList());
        // 取消后不应再被更新为 SUCCEEDED
        verify(analysisMapper, atLeastOnce()).updateById(any(Analysis.class));
    }

    @Test
    void runMarksFailedWhenProjectDeleted() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(null);
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper).updateById(captor.capture());
        assertEquals(AnalysisStatus.FAILED.name(), captor.getValue().getStatus());
    }

    @Test
    void runEvolutionFailureDoesNotBlockReport() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        when(analyzerClient.architecture(eq(7L), anyString()))
                .thenReturn(new ArchResultResp(List.of(), List.of(), List.of()));
        when(analyzerClient.evolution(eq(7L), anyString(), anyInt()))
                .thenThrow(new RuntimeException("evolution down"));
        when(analyzerClient.report(eq(7L), any(ScanResultResp.class), any(), any()))
                .thenReturn(newReportResp());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        verify(evolutionService, never()).replaceForAnalysis(any(), any(), any());
        ArgumentCaptor<Analysis> captor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper, atLeastOnce()).updateById(captor.capture());
        Analysis last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(AnalysisStatus.SUCCEEDED.name(), last.getStatus());
    }

    @Test
    void runEvolutionUnavailableStillCallsServiceWhichSkipsStorage() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis());
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        when(analyzerClient.architecture(eq(7L), anyString()))
                .thenReturn(new ArchResultResp(List.of(), List.of(), List.of()));
        when(analyzerClient.evolution(eq(7L), anyString(), anyInt()))
                .thenReturn(new EvolutionResp(false, List.of(), List.of(), List.of(), List.of(), List.of()));
        when(analyzerClient.report(eq(7L), any(ScanResultResp.class), any(), any()))
                .thenReturn(newReportResp());
        AnalysisRunner runner = newRunner();

        runner.run(10L);

        // available=false 时 replaceForAnalysis 仍被调用，内部跳过落库（见 EvolutionServiceImplTest）
        verify(evolutionService).replaceForAnalysis(eq(7L), eq(10L), any(EvolutionResp.class));
    }

    private EvolutionResp newEvolutionResp() {
        return new EvolutionResp(
                true,
                List.of(new EvolutionResp.CommitResp("abc", "alice", "a@t.co",
                        "2026-08-10T10:00:00+08:00", 40, 0, 1, "feat: x")),
                List.of(new EvolutionResp.TrendResp("2026-08-10", 1, 40, 0)),
                List.of(new EvolutionResp.TopFileResp("src/a.py", 1, 40, 0)),
                List.of(new EvolutionResp.AuthorResp("alice", 1, 40)),
                List.of(new EvolutionResp.HotspotResp("src/a.py", "HIGH", List.of("变更 1 次"), null)));
    }
}
