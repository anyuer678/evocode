package com.evocode.service.project;

import com.evocode.config.EvocodeProperties;
import com.evocode.entity.Project;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.AnalysisReportMapper;
import com.evocode.mapper.ArchViolationMapper;
import com.evocode.mapper.ArchitectureEdgeMapper;
import com.evocode.mapper.ArchitectureNodeMapper;
import com.evocode.mapper.ChatMessageMapper;
import com.evocode.mapper.ChatSessionMapper;
import com.evocode.mapper.CommitStatMapper;
import com.evocode.mapper.FileChangeStatMapper;
import com.evocode.mapper.FileNodeMapper;
import com.evocode.mapper.GeneratedDocMapper;
import com.evocode.mapper.HotspotMapper;
import com.evocode.mapper.KnowledgeChunkMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import com.evocode.mapper.TechDebtMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectDeleteService：删除级联清库 + 磁盘目录清理（06 §3.4）。
 * 由 ProjectServiceImplTest 下沉——逻辑随 delete 迁移至本服务。
 */
class ProjectDeleteServiceTest {

    @TempDir
    Path work;

    private ProjectMapper projectMapper;
    private AnalysisMapper analysisMapper;
    private FileNodeMapper fileNodeMapper;
    private QualityIssueMapper qualityIssueMapper;
    private ArchitectureNodeMapper architectureNodeMapper;
    private ArchitectureEdgeMapper architectureEdgeMapper;
    private ArchViolationMapper archViolationMapper;
    private CommitStatMapper commitStatMapper;
    private FileChangeStatMapper fileChangeStatMapper;
    private HotspotMapper hotspotMapper;
    private ChatSessionMapper chatSessionMapper;
    private ChatMessageMapper chatMessageMapper;
    private KnowledgeChunkMapper knowledgeChunkMapper;
    private TechDebtMapper techDebtMapper;
    private GeneratedDocMapper generatedDocMapper;
    private AnalysisReportMapper analysisReportMapper;
    private ProjectDeleteService service;

    @BeforeEach
    void setUp() {
        projectMapper = Mockito.mock(ProjectMapper.class);
        analysisMapper = Mockito.mock(AnalysisMapper.class);
        fileNodeMapper = Mockito.mock(FileNodeMapper.class);
        qualityIssueMapper = Mockito.mock(QualityIssueMapper.class);
        architectureNodeMapper = Mockito.mock(ArchitectureNodeMapper.class);
        architectureEdgeMapper = Mockito.mock(ArchitectureEdgeMapper.class);
        archViolationMapper = Mockito.mock(ArchViolationMapper.class);
        commitStatMapper = Mockito.mock(CommitStatMapper.class);
        fileChangeStatMapper = Mockito.mock(FileChangeStatMapper.class);
        hotspotMapper = Mockito.mock(HotspotMapper.class);
        chatSessionMapper = Mockito.mock(ChatSessionMapper.class);
        chatMessageMapper = Mockito.mock(ChatMessageMapper.class);
        knowledgeChunkMapper = Mockito.mock(KnowledgeChunkMapper.class);
        techDebtMapper = Mockito.mock(TechDebtMapper.class);
        generatedDocMapper = Mockito.mock(GeneratedDocMapper.class);
        analysisReportMapper = Mockito.mock(AnalysisReportMapper.class);
        EvocodeProperties props = new EvocodeProperties();
        props.setDataDir(work.resolve("data").toString());
        // 磁盘清理走真实存储服务（单测无活动事务 → isSynchronizationActive=false → 同步删盘）
        ProjectStorageService storageService = new ProjectStorageService(props);
        service = new ProjectDeleteService(projectMapper, analysisMapper, fileNodeMapper,
                qualityIssueMapper, architectureNodeMapper, architectureEdgeMapper,
                archViolationMapper, commitStatMapper, fileChangeStatMapper, hotspotMapper,
                chatSessionMapper, chatMessageMapper, knowledgeChunkMapper, techDebtMapper,
                generatedDocMapper, analysisReportMapper, storageService);
    }

    @Test
    void deleteRemovesDbRowsAndDiskDir() throws Exception {
        Path dir = work.resolve("data/projects/7");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "x");
        Project project = new Project();
        project.setId(7L);
        project.setStoragePath(dir.toString());
        when(projectMapper.selectById(7L)).thenReturn(project);

        service.delete(7L);

        verify(projectMapper).deleteById(7L);
        // 审查修订：P6/P7 新表级联（chat_message 先于 chat_session；knowledge_chunk 物理删）
        verify(chatMessageMapper).delete(any());
        verify(chatSessionMapper).delete(any());
        verify(knowledgeChunkMapper).deleteByProjectId(7L);
        verify(techDebtMapper).delete(any());
        verify(generatedDocMapper).delete(any());
        assertFalse(Files.exists(dir), "删除后磁盘目录应移除");
    }
}
