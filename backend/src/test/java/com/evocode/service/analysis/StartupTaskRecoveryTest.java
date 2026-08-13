package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AD-019：启动恢复——崩溃残留 PENDING/RUNNING 标记 FAILED + 项目状态恢复。 */
class StartupTaskRecoveryTest {

    private final AnalysisMapper analysisMapper = mock(AnalysisMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final StartupTaskRecovery recovery =
            new StartupTaskRecovery(analysisMapper, projectMapper);

    private Analysis stuckAnalysis(Long id, Long projectId, String status) {
        Analysis a = new Analysis();
        a.setId(id);
        a.setProjectId(projectId);
        a.setStatus(status);
        return a;
    }

    @Test
    void marksStuckAnalysesFailedAndRecoversProject() {
        Analysis a = stuckAnalysis(10L, 7L, AnalysisStatus.RUNNING.name());
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(a));
        Project p = new Project();
        p.setId(7L);
        p.setStatus(ProjectStatus.ANALYZING.name());
        when(projectMapper.selectById(7L)).thenReturn(p);

        recovery.run(null);

        assertEquals(AnalysisStatus.FAILED.name(), a.getStatus());
        assertEquals("服务重启导致任务中断，请重新发起分析", a.getErrorMessage());
        assertNotNull(a.getFinishedAt());
        ArgumentCaptor<Analysis> analysisCaptor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper).updateById(analysisCaptor.capture());
        assertEquals(10L, analysisCaptor.getValue().getId());
        assertEquals(ProjectStatus.READY.name(), p.getStatus());
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(projectCaptor.capture());
        assertEquals(7L, projectCaptor.getValue().getId());
    }

    @Test
    void noopWhenNoStuckTasks() {
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        recovery.run(null);
        verify(analysisMapper, never()).updateById(any(Analysis.class));
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    @Test
    void skipsDeletedProjectButStillFailsAnalysis() {
        Analysis a = stuckAnalysis(10L, 99L, AnalysisStatus.PENDING.name());
        when(analysisMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(a));
        when(projectMapper.selectById(99L)).thenReturn(null); // 项目已逻辑删除

        recovery.run(null);

        assertEquals(AnalysisStatus.FAILED.name(), a.getStatus());
        ArgumentCaptor<Analysis> analysisCaptor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper).updateById(analysisCaptor.capture());
        assertEquals(10L, analysisCaptor.getValue().getId());
        verify(projectMapper, never()).updateById(any(Project.class));
    }
}
