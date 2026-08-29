package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectUpdateReq;
import com.evocode.entity.Project;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectQueryService：列表排序白名单/默认方向、详情 2001、PATCH 更新校验。
 * 由 ProjectServiceImplTest 下沉——逻辑随 list/detail/update 迁移至本服务。
 */
class ProjectQueryServiceTest {

    private ProjectMapper projectMapper;
    private AnalysisMapper analysisMapper;
    private ProjectQueryService service;

    @BeforeEach
    void setUp() {
        projectMapper = Mockito.mock(ProjectMapper.class);
        analysisMapper = Mockito.mock(AnalysisMapper.class);
        service = new ProjectQueryService(projectMapper, analysisMapper);
    }

    @Test
    void detailNotFoundThrows2001() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.detail(99L));
        assertEquals(2001, e.getCode());
    }

    @Test
    void invalidSortRejected() {
        when(projectMapper.selectSummaryPage(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.list(1, 10, null, null, null, "malicious;drop", "asc"));
    }

    // 审查修复回归：契约 §6「时间类默认 desc」——sort 缺省等价 createdAt，应传 desc
    @Test
    void defaultSortIsDescForTimeColumns() {
        when(projectMapper.selectSummaryPage(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        // sort=null（默认 createdAt）→ desc
        service.list(1, 10, null, null, null, null, null);
        org.mockito.Mockito.verify(projectMapper).selectSummaryPage(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("p.created_at"),
                org.mockito.ArgumentMatchers.eq("desc"));
    }

    // ---- P9b：PATCH 更新 ----

    @Test
    void update_renameAndDescription_ok() {
        Project p = new Project();
        p.setId(7L);
        p.setName("old");
        p.setDescription(null);
        when(projectMapper.selectById(7L)).thenReturn(p);
        doAnswer(inv -> {
            Project merged = new Project();
            merged.setId(7L);
            merged.setName("new-name");
            merged.setDescription("desc");
            when(projectMapper.selectById(7L)).thenReturn(merged);
            return 1;
        }).when(projectMapper).update(any(), any());

        ProjectResp resp = service.update(7L, new ProjectUpdateReq("new-name", "desc"));
        assertEquals("new-name", resp.getName());
        assertEquals("desc", resp.getDescription());
        verify(projectMapper).update(any(), any());
    }

    @Test
    void update_emptyReq_throws1001() {
        Project p = new Project();
        p.setId(7L);
        when(projectMapper.selectById(7L)).thenReturn(p);
        assertThrows(BusinessException.class,
                () -> service.update(7L, new ProjectUpdateReq(null, "  ")));
    }

    @Test
    void update_blankName_throws1002() {
        assertThrows(BusinessException.class,
                () -> service.update(7L, new ProjectUpdateReq("  ", null)));
    }

    @Test
    void update_nameTooLong_throws1002() {
        assertThrows(BusinessException.class,
                () -> service.update(7L, new ProjectUpdateReq("x".repeat(101), null)));
    }

    @Test
    void update_projectNotFound_throws2001() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.update(99L, new ProjectUpdateReq("new", null)));
    }

    // ---- P9e：排序白名单 ----

    @Test
    void list_healthScoreSortMapsNullsLast() {
        // SPI-6：healthScore → health_score 列 + DESC NULLS LAST（PG 语法要求 NULLS 在 ASC/DESC 之后）
        service.list(1, 10, null, null, null, "healthScore", "desc");
        verify(projectMapper).selectSummaryPage(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq("health_score"),
                org.mockito.ArgumentMatchers.eq("desc NULLS LAST"));
    }

    @Test
    void list_unknownSortThrows1002() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.list(1, 10, null, null, null, "evil; DROP", "desc"));
        assertEquals(1002, e.getCode());
    }
}
