package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.dto.analysis.AnalysisResp;
import com.evocode.dto.analysis.AnalysisStatusResp;
import com.evocode.dto.analysis.ReportHistoryResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.AnalysisReport;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.Stage;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.report.ReportStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-U（P2a）：发起分析排他与校验、状态轮询。
 */
class AnalysisServiceTest {

    private final AnalysisMapper analysisMapper = mock(AnalysisMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AnalysisRunner runner = mock(AnalysisRunner.class);
    private final ReportStorageService reportStorageService = mock(ReportStorageService.class);
    private AnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AnalysisServiceImpl(analysisMapper, projectMapper, runner, reportStorageService);
    }

    private Project newProject() {
        Project p = new Project();
        p.setId(7L);
        return p;
    }

    private Analysis newAnalysis() {
        Analysis a = new Analysis();
        a.setId(10L);
        a.setProjectId(7L);
        a.setType("FULL");
        return a;
    }

    private AnalysisReport newReport(Integer healthScore, String level, Map<String, Object> reportJson) {
        AnalysisReport r = new AnalysisReport();
        r.setId(1L);
        r.setAnalysisId(10L);
        r.setHealthScore(healthScore);
        r.setLevel(level);
        r.setReportJson(reportJson);
        return r;
    }

    @Test
    void createInsertsPendingAndTriggersRunner() {
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analysisMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        // mock insert 不落库：回填自增 id，供 run(id) 验证
        when(analysisMapper.insert(any(Analysis.class))).thenAnswer(inv -> {
            inv.getArgument(0, Analysis.class).setId(10L);
            return 1;
        });

        AnalysisResp resp = service.create(7L, "FULL");

        assertEquals(7L, resp.projectId());
        assertEquals(AnalysisStatus.PENDING.name(), resp.status());
        assertEquals(Stage.QUEUED.name(), resp.stage());
        assertEquals(0, resp.progress());
        verify(runner).run(10L);
    }

    @Test
    void createRejectsUnknownProject() {
        when(projectMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(7L, "FULL"));
        assertEquals(2001, e.getCode());
    }

    @Test
    void createRejectsUnsupportedType() {
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(7L, "QUALITY"));
        assertEquals(1002, e.getCode());
    }

    @Test
    void createRejectsWhenRunningExists() {
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analysisMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        BusinessException e = assertThrows(BusinessException.class, () -> service.create(7L, "FULL"));
        assertEquals(2002, e.getCode());
    }

    @Test
    void statusReturnsCurrentState() {
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.RUNNING.name());
        a.setProgress(45);
        a.setStage(Stage.SCAN.name());
        when(analysisMapper.selectById(10L)).thenReturn(a);

        AnalysisStatusResp resp = service.status(10L);
        assertEquals(AnalysisStatus.RUNNING.name(), resp.status());
        assertEquals(45, resp.progress());
        assertEquals(Stage.SCAN.name(), resp.stage());
    }

    @Test
    void statusRejectsMissingAnalysis() {
        when(analysisMapper.selectById(10L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.status(10L));
        assertEquals(2001, e.getCode());
    }

    @Test
    void historyRejectsUnknownProject() {
        when(projectMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.history(7L, 1, 10));
        assertEquals(2001, e.getCode());
    }

    @Test
    void reportReturnsStoredReport() {
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.SUCCEEDED.name());
        a.setReportSource("RULES");
        a.setPromptVersion("report-1.0");
        when(analysisMapper.selectById(10L)).thenReturn(a);
        when(reportStorageService.getByAnalysisId(10L))
                .thenReturn(newReport(82, "GOOD", Map.of("healthScore", 82)));

        var resp = service.report(10L);
        assertEquals(10L, resp.analysisId());
        assertEquals("RULES", resp.source());
        assertEquals("report-1.0", resp.promptVersion());
        assertEquals(82, resp.report().get("healthScore"));
    }

    @Test
    void reportRejectsWhenMissing() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis()); // 无报告
        when(reportStorageService.getByAnalysisId(10L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.report(10L));
        assertEquals(2001, e.getCode());
    }

    @Test
    void regenerateRejectsWhenRunning() {
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.RUNNING.name());
        when(analysisMapper.selectById(10L)).thenReturn(a);
        when(reportStorageService.getByAnalysisId(10L)).thenReturn(newReport(82, "GOOD", Map.of()));
        BusinessException e = assertThrows(BusinessException.class, () -> service.regenerate(10L));
        assertEquals(2008, e.getCode());
    }

    @Test
    void regenerateTriggersRunner() {
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.SUCCEEDED.name());
        when(analysisMapper.selectById(10L)).thenReturn(a);
        when(reportStorageService.getByAnalysisId(10L)).thenReturn(newReport(82, "GOOD", Map.of()));

        var resp = service.regenerate(10L);
        assertEquals(AnalysisStatus.RUNNING.name(), resp.status());
        assertEquals(Stage.REPORT.name(), resp.stage());
        verify(runner).regenerateReport(10L);
    }

    // ---- P9c：报告历史 ----

    @Test
    void reportHistoryRejectsUnknownProject() {
        when(projectMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.reportHistory(7L, 10));
        assertEquals(2001, e.getCode());
    }

    @Test
    void reportHistoryQueriesSucceededWithReport() {
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        List<ReportHistoryResp> resp = service.reportHistory(7L, 10);
        assertEquals(0, resp.size());
        // 校验查询条件：project_id + SUCCEEDED + 倒序（报告已拆表，不再按 report_json 过滤）
        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper).selectList(captor.capture());
        QueryWrapper<?> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("project_id ="));
        assertTrue(wrapper.getSqlSegment().contains("status ="));
        assertTrue(wrapper.getSqlSegment().contains("ORDER BY id DESC"));
        assertTrue(wrapper.getSqlSegment().contains("LIMIT"));
    }

    @Test
    void reportHistoryExtractsSummary() {
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.SUCCEEDED.name());
        a.setFinishedAt(OffsetDateTime.parse("2026-08-10T10:01:00Z"));
        a.setReportSource("RULES");
        // healthScore/level 走 analysis_report 列；dimensions/risks 从 report_json 解析
        when(reportStorageService.getByAnalysisId(10L)).thenReturn(newReport(82, "GOOD", Map.of(
                "dimensions", List.of(
                        Map.of("key", "quality", "score", 76, "stars", 4),
                        Map.of("key", "structure", "score", 88, "stars", 4)),
                "risks", List.of(
                        Map.of("level", "HIGH", "title", "Spring Boot 2.5 已停止官方支持")))));
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(a));

        List<ReportHistoryResp> resp = service.reportHistory(7L, 10);
        assertEquals(1, resp.size());
        ReportHistoryResp item = resp.get(0);
        assertEquals(10L, item.analysisId());
        assertEquals(82, item.healthScore());
        assertEquals("GOOD", item.level());
        assertEquals(2, item.dimensions().size());
        assertEquals("quality", item.dimensions().get(0).key());
        assertEquals(76, item.dimensions().get(0).score());
        assertEquals(4, item.dimensions().get(0).stars());
        assertEquals(1, item.risks().size());
        assertEquals("HIGH", item.risks().get(0).level());
        assertEquals("Spring Boot 2.5 已停止官方支持", item.risks().get(0).title());
        assertEquals("RULES", item.source());
    }

    @Test
    void reportHistoryDefendsNonNumericHealthScore() {
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.SUCCEEDED.name());
        // 非数字 healthScore → analysis_report 列存 NULL（V010 迁移数值防御）
        when(reportStorageService.getByAnalysisId(10L)).thenReturn(newReport(null, "GOOD", Map.of()));
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(a));

        List<ReportHistoryResp> resp = service.reportHistory(7L, 10);
        assertEquals(1, resp.size());
        // 非 Number → null（前端显示 —）
        assertEquals(null, resp.get(0).healthScore());
        assertEquals("GOOD", resp.get(0).level());
    }

    @Test
    void reportHistoryClampsLimit() {
        // P9c：limit 二次钳制（service 兜底）——0 → 1，100 → 20
        when(projectMapper.selectById(7L)).thenReturn(newProject());
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.reportHistory(7L, 0);
        service.reportHistory(7L, 100);

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(analysisMapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        var sqls = captor.getAllValues().stream()
                .map(QueryWrapper::getSqlSegment).toList();
        assertTrue(sqls.get(0).contains("LIMIT 1"));
        assertTrue(sqls.get(1).contains("LIMIT 20"));
    }
}
