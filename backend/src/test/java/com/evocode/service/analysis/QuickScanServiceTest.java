package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.dto.scan.ScanResultResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.scan.FileNodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-U-27/28：快扫成功（RUNNING→READY + file_node 快照 + 档案更新）/ 重复触发幂等（不新建任务）。
 */
class QuickScanServiceTest {

    @TempDir
    Path tempDir;

    private final AnalysisMapper analysisMapper = mock(AnalysisMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final AnalyzerClient analyzerClient = mock(AnalyzerClient.class);
    private final FileNodeService fileNodeService = mock(FileNodeService.class);

    private QuickScanService newService() {
        return new QuickScanService(analysisMapper, projectMapper, analyzerClient, fileNodeService);
    }

    private Project newProject() {
        Project project = new Project();
        project.setId(7L);
        project.setStoragePath(tempDir.resolve("p7").toString());
        return project;
    }

    private ScanResultResp newScan() {
        return new ScanResultResp(
                Map.of("Java", 100.0),
                120L,
                3,
                0,
                List.of("Spring Boot"),
                true,
                false,
                List.of(),
                List.of(),
                1,
                false);
    }

    @Test
    void quickScanSuccessWritesSnapshotAndArchive() {
        when(analysisMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(analyzerClient.scan(eq(7L), anyString())).thenReturn(newScan());
        QuickScanService service = newService();

        service.quickScan(newProject());

        verify(analysisMapper).insert(any(Analysis.class));

        verify(analyzerClient).scan(eq(7L), anyString());
        verify(fileNodeService).replaceSnapshot(eq(7L), any(), anyList());

        ArgumentCaptor<Analysis> updateCaptor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper).updateById(updateCaptor.capture());
        assertEquals(AnalysisStatus.SUCCEEDED.name(), updateCaptor.getValue().getStatus());
        assertEquals("SCAN_DONE", updateCaptor.getValue().getStage());

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper, atLeastOnce()).updateById(projectCaptor.capture());
        List<Project> updates = projectCaptor.getAllValues();
        assertEquals(ProjectStatus.READY.name(), updates.get(updates.size() - 1).getStatus());
    }

    @Test
    void quickScanSkipsWhenAlreadyRunning() {
        when(analysisMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        QuickScanService service = newService();

        service.quickScan(newProject());

        verify(analysisMapper, never()).insert(any(Analysis.class));
        verify(analyzerClient, never()).scan(any(), anyString());
        verify(fileNodeService, never()).replaceSnapshot(any(), any(), anyList());
    }

    @Test
    void quickScanFailureMarksFailed() {
        when(analysisMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(analyzerClient.scan(eq(7L), anyString())).thenThrow(new RuntimeException("analyzer down"));
        QuickScanService service = newService();

        service.quickScan(newProject());

        ArgumentCaptor<Analysis> updateCaptor = ArgumentCaptor.forClass(Analysis.class);
        verify(analysisMapper).updateById(updateCaptor.capture());
        assertEquals(AnalysisStatus.FAILED.name(), updateCaptor.getValue().getStatus());
    }
}
