package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.config.EvocodeProperties;
import com.evocode.dto.project.ProjectResp;
import com.evocode.mapper.AnalysisMapper;
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
import com.evocode.entity.Project;
import com.evocode.service.analysis.QuickScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectService 核心流：zip 创建（含磁盘落位）、Git 创建、删除级联清理。
 */
class ProjectServiceImplTest {

    @TempDir
    Path work;

    private ProjectMapper projectMapper;
    private AnalysisMapper analysisMapper;
    private FileNodeMapper fileNodeMapper;
    private UploadService uploadService;
    private GitCloneService gitCloneService;
    private QuickScanService quickScanService;
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
    private EvocodeProperties props;
    private ProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        projectMapper = Mockito.mock(ProjectMapper.class);
        analysisMapper = Mockito.mock(AnalysisMapper.class);
        fileNodeMapper = Mockito.mock(FileNodeMapper.class);
        uploadService = Mockito.mock(UploadService.class);
        gitCloneService = Mockito.mock(GitCloneService.class);
        quickScanService = Mockito.mock(QuickScanService.class);
        props = new EvocodeProperties();
        props.setDataDir(work.resolve("data").toString());
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
        service = new ProjectServiceImpl(projectMapper, analysisMapper, fileNodeMapper,
                qualityIssueMapper, architectureNodeMapper, architectureEdgeMapper,
                archViolationMapper, commitStatMapper, fileChangeStatMapper, hotspotMapper,
                chatSessionMapper, chatMessageMapper, knowledgeChunkMapper, techDebtMapper,
                generatedDocMapper, uploadService, gitCloneService, quickScanService, props);
        doAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
    }

    @Test
    void createFromZipStoresCodeAndTriggersQuickScan() throws Exception {
        Path tempRoot = Files.createTempDirectory("upload-test");
        Path root = tempRoot.resolve("chatez");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src").resolve("App.java"), "class App {}");
        when(uploadService.extractZip(any(), any())).thenReturn(root);

        ProjectResp resp = service.createFromZip("Chatez", "demo", new MockMultipartFile("f", new byte[0]));

        assertEquals("ZIP", resp.getSourceType());
        assertEquals("data/projects/1", resp.getStoragePath());
        Path stored = work.resolve("data/projects/1/src/App.java");
        assertTrue(Files.exists(stored), "代码应原子移入 data/projects/{id}");
        verify(quickScanService).quickScan(any(Project.class));
    }

    @Test
    void createFromGitSetsSourceAndRepoUrl() throws Exception {
        Path tempRoot = Files.createTempDirectory("clone-test");
        Path repo = tempRoot.resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("README.md"), "# r");
        doAnswer(inv -> {
            Files.createDirectories(inv.getArgument(2));
            Files.writeString(Path.of(inv.getArgument(2).toString()).resolve("README.md"), "# r");
            return null;
        }).when(gitCloneService).clone(any(), Mockito.anyInt(), any());

        ProjectResp resp = service.createFromGit("Chatez", null, "https://github.com/owner/chatez", 1);

        assertEquals("GIT", resp.getSourceType());
        assertTrue(Files.exists(work.resolve("data/projects/1/README.md")));
        verify(quickScanService).quickScan(any(Project.class));
    }

    @Test
    void detailNotFoundThrows2001() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () -> service.detail(99L));
        assertEquals(2001, e.getCode());
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

    @Test
    void invalidSortRejected() {
        when(projectMapper.selectSummaryPage(any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.list(1, 10, null, null, null, "malicious;drop", "asc"));
    }
}
