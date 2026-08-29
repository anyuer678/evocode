package com.evocode.service.project;

import com.evocode.config.EvocodeProperties;
import com.evocode.dto.project.ProjectResp;
import com.evocode.entity.Project;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.analysis.QuickScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectLifecycleService：zip 创建（含磁盘落位）与 Git 创建（06 §3.1）。
 * 由 ProjectServiceImplTest 下沉——逻辑随 createFromZip/createFromGit 迁移至本服务。
 */
class ProjectLifecycleServiceTest {

    @TempDir
    Path work;

    private ProjectMapper projectMapper;
    private UploadService uploadService;
    private GitCloneService gitCloneService;
    private QuickScanService quickScanService;
    private ProjectLifecycleService service;

    @BeforeEach
    void setUp() {
        projectMapper = Mockito.mock(ProjectMapper.class);
        uploadService = Mockito.mock(UploadService.class);
        gitCloneService = Mockito.mock(GitCloneService.class);
        quickScanService = Mockito.mock(QuickScanService.class);
        EvocodeProperties props = new EvocodeProperties();
        props.setDataDir(work.resolve("data").toString());
        ProjectStorageService storageService = new ProjectStorageService(props);
        service = new ProjectLifecycleService(projectMapper, uploadService, gitCloneService,
                quickScanService, storageService);
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
        assertEquals(Path.of(work.resolve("data").toString(), "projects", "1").toString(),
                resp.getStoragePath());
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
}
