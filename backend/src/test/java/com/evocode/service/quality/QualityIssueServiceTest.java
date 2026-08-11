package com.evocode.service.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.dto.quality.QualityIssuesResp;
import com.evocode.entity.QualityIssue;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T-U（P3c）：质量 issues 查询——筛选/分页/指标聚合/项目校验。
 */
class QualityIssueServiceTest {

    private final QualityIssueMapper mapper = Mockito.mock(QualityIssueMapper.class);
    private final ProjectMapper projectMapper = Mockito.mock(ProjectMapper.class);
    private QualityIssueServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QualityIssueServiceImpl(mapper, projectMapper);
    }

    private QualityIssue issue(long id, String kind, String severity, String filePath) {
        QualityIssue i = new QualityIssue();
        i.setId(id);
        i.setProjectId(7L);
        i.setKind(kind);
        i.setSeverity(severity);
        i.setFilePath(filePath);
        i.setRuleKey("r1");
        i.setMessage("m");
        i.setAiStatus("PENDING");
        i.setStatus("OPEN");
        return i;
    }

    @Test
    void rejectsUnknownProject() {
        when(projectMapper.selectById(7L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.query(7L, null, null, null, 1, 10));
        assertEquals(2001, e.getCode());
    }

    @Test
    void emptyResultMeansSonarNotEnabled() {
        when(projectMapper.selectById(7L)).thenReturn(Mockito.mock(com.evocode.entity.Project.class));
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<QualityIssue>().setRecords(List.of()));

        QualityIssuesResp resp = service.query(7L, null, null, null, 1, 10);

        assertEquals(false, resp.metrics().available());
        assertEquals(0, resp.total());
        assertEquals(0, resp.items().size());
    }

    @Test
    void aggregatesMetricsByKind() {
        when(projectMapper.selectById(7L)).thenReturn(Mockito.mock(com.evocode.entity.Project.class));
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                issue(1, "BUG", "MAJOR", "a.py"),
                issue(2, "BUG", "CRITICAL", "b.py"),
                issue(3, "VULNERABILITY", "BLOCKER", "c.py")));
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<QualityIssue>().setRecords(List.of(
                        issue(1, "BUG", "MAJOR", "a.py"))));

        QualityIssuesResp resp = service.query(7L, null, null, null, 1, 10);

        assertEquals(true, resp.metrics().available());
        assertEquals(2, resp.metrics().bugs());
        assertEquals(1, resp.metrics().vulnerabilities());
        assertEquals(0, resp.metrics().codeSmells());
        assertEquals(3, resp.total());
        assertEquals(1, resp.items().size());
        assertEquals("BUG", resp.items().get(0).kind());
    }
}
