package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** ReportExportService（P9b）：Markdown 导出纯字符串。 */
class ReportExportServiceTest {

    private AnalysisMapper analysisMapper;
    private ProjectMapper projectMapper;
    private ReportExportService service;

    @BeforeEach
    void setUp() {
        analysisMapper = Mockito.mock(AnalysisMapper.class);
        projectMapper = Mockito.mock(ProjectMapper.class);
        service = new ReportExportService(analysisMapper, projectMapper);
    }

    private Analysis analysisWithReport() {
        Analysis a = new Analysis();
        a.setId(42L);
        a.setStatus("SUCCEEDED");
        a.setFinishedAt(OffsetDateTime.parse("2026-08-11T10:00:00+08:00"));
        a.setReportSource("RULES");
        a.setReportJson(Map.of(
                "healthScore", 82,
                "level", "GOOD",
                "summary", "整体健康",
                "dimensions", List.of(
                        Map.of("key", "quality", "score", 80, "stars", 4, "summary", "质量良好"),
                        Map.of("key", "structure", "score", 70, "stars", 4, "summary", "结构一般"),
                        Map.of("key", "dependency", "score", 90, "stars", 5, "summary", "依赖干净"),
                        Map.of("key", "scale", "score", 60, "stars", 3, "summary", "规模偏大")),
                "risks", List.of(Map.of(
                        "level", "HIGH", "title", "循环依赖",
                        "detail", "A→B→A", "suggestion", "分层", "references", List.of("x.java:1"))),
                "recommendations", List.of(Map.of(
                        "phase", "近期", "items", List.of("拆分模块", "补充测试")))));
        return a;
    }

    @Test
    void exportLatest_includesMainFields() {
        Project p = new Project();
        p.setId(7L);
        p.setName("demo");
        when(projectMapper.selectById(7L)).thenReturn(p);
        when(analysisMapper.selectOne(any())).thenReturn(analysisWithReport());

        String md = service.exportLatest(7L);
        assertTrue(md.contains("# EvoCode 体检报告 —— demo"));
        assertTrue(md.contains("82/100"));
        assertTrue(md.contains("质量"));
        assertTrue(md.contains("循环依赖"));
        assertTrue(md.contains("拆分模块"));
        assertTrue(md.contains("RULES"));
    }

    @Test
    void exportLatest_projectNotFound_throws2001() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.exportLatest(99L));
    }

    @Test
    void exportLatest_noReport_throws2001() {
        Project p = new Project();
        p.setId(7L);
        when(projectMapper.selectById(7L)).thenReturn(p);
        when(analysisMapper.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.exportLatest(7L));
    }
}
