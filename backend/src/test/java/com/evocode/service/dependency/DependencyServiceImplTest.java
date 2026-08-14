package com.evocode.service.dependency;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.evocode.common.BusinessException;
import com.evocode.dto.dependency.DependencyResp;
import com.evocode.entity.Dependency;
import com.evocode.entity.Project;
import com.evocode.mapper.DependencyMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-U（P9d）：依赖清单落库/清空/查询。 */
class DependencyServiceImplTest {

    private final DependencyMapper dependencyMapper = mock(DependencyMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private DependencyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DependencyServiceImpl(dependencyMapper, projectMapper);
    }

    private Project project() {
        Project p = new Project();
        p.setId(1L);
        return p;
    }

    @Test
    void replaceInsertsRowsForAnalysis() {
        DependencyResp resp = new DependencyResp(true, List.of(
                new DependencyResp.ItemResp("org.springframework.boot:spring-boot",
                        "2.5.14", "MAVEN", "pom.xml", "HIGH", "已 EOL", "3.2+", true, null),
                new DependencyResp.ItemResp("vue", "2.6.14", "NPM", "package.json",
                        "HIGH", "Vue2 EOL", "3.x", true, null)));

        service.replaceForAnalysis(1L, 10L, resp);

        verify(dependencyMapper).delete(any(Wrapper.class));
        verify(dependencyMapper, org.mockito.Mockito.times(2)).insert(any(Dependency.class));
    }

    // 审查修复回归：suggestion 应为可操作建议（EOL → 升级 latest），非 EOL 原因原文
    @Test
    void replaceSuggestionIsActionableForEol() {
        DependencyResp resp = new DependencyResp(true, List.of(
                new DependencyResp.ItemResp("vue", "2.6.14", "NPM", "package.json",
                        "HIGH", "Vue 2 已停止官方支持", "3.x", true, null),
                new DependencyResp.ItemResp("ok-lib", "1.0.0", "NPM", "package.json",
                        null, null, null, false, null)));
        service.replaceForAnalysis(1L, 10L, resp);
        var captor = org.mockito.ArgumentCaptor.forClass(Dependency.class);
        verify(dependencyMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        var inserted = captor.getAllValues();
        Dependency eol = inserted.stream()
                .filter(d -> "vue".equals(d.getName())).findFirst().orElseThrow();
        assertEquals("升级到 3.x（当前版本已停止支持）", eol.getSuggestion());
        Dependency ok = inserted.stream()
                .filter(d -> "ok-lib".equals(d.getName())).findFirst().orElseThrow();
        assertEquals(null, ok.getSuggestion());
    }

    @Test
    void replaceClearsProjectWhenUnavailable() {
        // available=false（无 Maven/npm 依赖文件）→ 清空项目，不插入
        service.replaceForAnalysis(1L, 10L, new DependencyResp(false, List.of()));
        verify(dependencyMapper).delete(any(Wrapper.class));
        verify(dependencyMapper, never()).insert(any(Dependency.class));
    }

    @Test
    void replaceNullKeepsOldData() {
        service.replaceForAnalysis(1L, 10L, null);
        verify(dependencyMapper, never()).delete(any(Wrapper.class));
        verify(dependencyMapper, never()).insert(any(Dependency.class));
    }

    @Test
    void getForProjectRejectsMissingProject() {
        when(projectMapper.selectById(1L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getForProject(1L));
        assertEquals(2001, e.getCode());
    }

    @Test
    void getForProjectNoDataReturnsUnavailable() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(dependencyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        DependencyResp resp = service.getForProject(1L);
        assertTrue(!resp.available());
        assertTrue(resp.dependencies().isEmpty());
    }

    @Test
    void getForProjectReturnsLatestAnalysisRows() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        Dependency sample = new Dependency();
        sample.setAnalysisId(10L);
        when(dependencyMapper.selectOne(any(Wrapper.class))).thenReturn(sample);

        Dependency row = new Dependency();
        row.setName("vue");
        row.setVersion("2.6.14");
        row.setEcosystem("npm");
        row.setRiskLevel("HIGH");
        row.setRiskReason("Vue2 EOL");
        row.setLatestVersion("3.x");
        row.setIsEol(true);
        row.setSuggestion("升级到 3.x（当前版本已停止支持）");
        when(dependencyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

        DependencyResp resp = service.getForProject(1L);
        assertTrue(resp.available());
        assertEquals(1, resp.dependencies().size());
        DependencyResp.ItemResp item = resp.dependencies().get(0);
        assertEquals("vue", item.name());
        assertEquals("2.6.14", item.version());
        assertEquals("NPM", item.type());
        assertEquals("HIGH", item.risk());
        assertTrue(item.isEol());
        assertEquals("升级到 3.x（当前版本已停止支持）", item.suggestion());
    }
}
