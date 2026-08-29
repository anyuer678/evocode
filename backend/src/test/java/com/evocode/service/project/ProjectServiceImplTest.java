package com.evocode.service.project;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.project.ProjectDetailResp;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.dto.project.ProjectUpdateReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectServiceImpl 门面委托测试：Controller 只面对 ProjectService 接口，
 * 门面按领域转发至 ProjectLifecycleService / ProjectQueryService / ProjectDeleteService，
 * 并保留缓存与事务注解位置。领域逻辑断言见各子服务测试类
 * （ProjectLifecycleServiceTest / ProjectQueryServiceTest / ProjectDeleteServiceTest）。
 */
class ProjectServiceImplTest {

    private ProjectLifecycleService lifecycleService;
    private ProjectQueryService queryService;
    private ProjectDeleteService deleteService;
    private ProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        lifecycleService = Mockito.mock(ProjectLifecycleService.class);
        queryService = Mockito.mock(ProjectQueryService.class);
        deleteService = Mockito.mock(ProjectDeleteService.class);
        service = new ProjectServiceImpl(lifecycleService, queryService, deleteService);
    }

    @Test
    void createFromZip_delegatesToLifecycle() {
        ProjectResp resp = ProjectResp.builder().build();
        MockMultipartFile file = new MockMultipartFile("f", new byte[0]);
        when(lifecycleService.createFromZip("Chatez", "demo", file)).thenReturn(resp);

        assertSame(resp, service.createFromZip("Chatez", "demo", file));
        verify(lifecycleService).createFromZip("Chatez", "demo", file);
    }

    @Test
    void createFromZip_propagatesBusinessException() {
        MockMultipartFile file = new MockMultipartFile("f", new byte[0]);
        when(lifecycleService.createFromZip(any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.FILE_ILLEGAL, "boom"));

        assertThrows(BusinessException.class, () -> service.createFromZip("x", null, file));
    }

    @Test
    void createFromGit_delegatesToLifecycle() {
        ProjectResp resp = ProjectResp.builder().build();
        when(lifecycleService.createFromGit("Chatez", null, "https://github.com/o/r", 1)).thenReturn(resp);

        assertSame(resp, service.createFromGit("Chatez", null, "https://github.com/o/r", 1));
        verify(lifecycleService).createFromGit("Chatez", null, "https://github.com/o/r", 1);
    }

    @Test
    void list_delegatesToQueryWithAllParams() {
        @SuppressWarnings("unchecked")
        IPage<ProjectSummaryResp> page = Mockito.mock(IPage.class);
        when(queryService.list(1, 10, "kw", "Java", "ACTIVE", "name", "asc")).thenReturn(page);

        assertSame(page, service.list(1, 10, "kw", "Java", "ACTIVE", "name", "asc"));
        verify(queryService).list(1, 10, "kw", "Java", "ACTIVE", "name", "asc");
    }

    @Test
    void detail_delegatesToQuery() {
        ProjectDetailResp detail = ProjectDetailResp.builder().build();
        when(queryService.detail(7L)).thenReturn(detail);

        assertSame(detail, service.detail(7L));
        verify(queryService).detail(7L);
    }

    @Test
    void update_delegatesToQuery() {
        ProjectUpdateReq req = new ProjectUpdateReq("new-name", "desc");
        ProjectResp resp = ProjectResp.builder().build();
        when(queryService.update(7L, req)).thenReturn(resp);

        assertSame(resp, service.update(7L, req));
        verify(queryService).update(7L, req);
    }

    @Test
    void delete_delegatesToDeleteService() {
        service.delete(7L);
        verify(deleteService).delete(7L);
    }

    @Test
    void delete_propagatesBusinessException() {
        doThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null))
                .when(deleteService).delete(anyLong());

        assertThrows(BusinessException.class, () -> service.delete(99L));
    }
}
