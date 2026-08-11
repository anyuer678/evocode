package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.dto.analysis.AnalysisResp;
import com.evocode.dto.analysis.AnalysisStatusResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.Stage;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private AnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AnalysisServiceImpl(analysisMapper, projectMapper, runner);
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
        a.setReportJson(Map.of("healthScore", 82));
        a.setReportSource("RULES");
        a.setPromptVersion("report-1.0");
        when(analysisMapper.selectById(10L)).thenReturn(a);

        var resp = service.report(10L);
        assertEquals(10L, resp.analysisId());
        assertEquals("RULES", resp.source());
        assertEquals("report-1.0", resp.promptVersion());
        assertEquals(82, resp.report().get("healthScore"));
    }

    @Test
    void reportRejectsWhenMissing() {
        when(analysisMapper.selectById(10L)).thenReturn(newAnalysis()); // 无 report_json
        BusinessException e = assertThrows(BusinessException.class, () -> service.report(10L));
        assertEquals(2001, e.getCode());
    }

    @Test
    void regenerateRejectsWhenRunning() {
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.RUNNING.name());
        a.setReportJson(Map.of("healthScore", 82));
        when(analysisMapper.selectById(10L)).thenReturn(a);
        BusinessException e = assertThrows(BusinessException.class, () -> service.regenerate(10L));
        assertEquals(2008, e.getCode());
    }

    @Test
    void regenerateTriggersRunner() {
        Analysis a = newAnalysis();
        a.setStatus(AnalysisStatus.SUCCEEDED.name());
        a.setReportJson(Map.of("healthScore", 82));
        when(analysisMapper.selectById(10L)).thenReturn(a);

        var resp = service.regenerate(10L);
        assertEquals(AnalysisStatus.RUNNING.name(), resp.status());
        assertEquals(Stage.REPORT.name(), resp.stage());
        verify(runner).regenerateReport(10L);
    }
}
