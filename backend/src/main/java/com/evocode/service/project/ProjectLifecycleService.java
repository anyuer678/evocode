package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.project.ProjectResp;
import com.evocode.entity.Project;
import com.evocode.enums.ProjectSourceType;
import com.evocode.enums.ProjectStatus;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.analysis.QuickScanService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 项目创建编排（06 §3.1 方式 A/B）：zip 解压 / Git 克隆 → 落库 → 目录移入存储 → 异步快扫。
 * 从 ProjectServiceImpl 抽出——仅依赖 upload/git/quickScan/projectMapper/storage 五个协作者。
 * 事务边界：故意不加 @Transactional——解压与克隆是长磁盘 IO，不应占用 DB 连接；
 * insertProject 为单条插入（隐式事务），目录移入失败用 deleteById 手动补偿（06 §3.1）。
 */
@Service
public class ProjectLifecycleService {

    private final ProjectMapper projectMapper;
    private final UploadService uploadService;
    private final GitCloneService gitCloneService;
    private final QuickScanService quickScanService;
    private final ProjectStorageService storageService;

    public ProjectLifecycleService(ProjectMapper projectMapper,
                                   UploadService uploadService,
                                   GitCloneService gitCloneService,
                                   QuickScanService quickScanService,
                                   ProjectStorageService storageService) {
        this.projectMapper = projectMapper;
        this.uploadService = uploadService;
        this.gitCloneService = gitCloneService;
        this.quickScanService = quickScanService;
        this.storageService = storageService;
    }

    public ProjectResp createFromZip(String name, String description, MultipartFile file) {
        Path tempDir = storageService.createTempDir();
        try {
            Path root = uploadService.extractZip(tempDir, file);
            Project project = insertProject(name, description, ProjectSourceType.ZIP.name(), null);
            try {
                storageService.moveDir(root, storageService.storagePathOf(project.getId()));
            } catch (IOException e) {
                projectMapper.deleteById(project.getId());
                throw new BusinessException(ErrorCode.FILE_ILLEGAL, "代码移入存储失败：" + e.getMessage());
            }
            project.setStoragePath(storageService.relStoragePath(project.getId()));
            projectMapper.updateById(project);
            quickScanService.quickScan(project);
            return toResp(project);
        } catch (BusinessException e) {
            throw e;
        } finally {
            storageService.deleteRecursive(tempDir);
        }
    }

    public ProjectResp createFromGit(String name, String description, String repoUrl, Integer cloneDepth) {
        int depth = cloneDepth == null ? 1 : cloneDepth;
        if (depth < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "cloneDepth 非法（0=全量，>0 为 depth）");
        }
        Path tempDir = storageService.createTempDir();
        try {
            Path repoDir = tempDir.resolve("repo");
            gitCloneService.clone(repoUrl, depth, repoDir);
            Project project = insertProject(name, description, ProjectSourceType.GIT.name(), repoUrl.trim());
            try {
                storageService.moveDir(repoDir, storageService.storagePathOf(project.getId()));
            } catch (IOException e) {
                projectMapper.deleteById(project.getId());
                throw new BusinessException(ErrorCode.GIT_CLONE_FAILED, "代码移入存储失败：" + e.getMessage());
            }
            project.setStoragePath(storageService.relStoragePath(project.getId()));
            projectMapper.updateById(project);
            quickScanService.quickScan(project);
            return toResp(project);
        } catch (BusinessException e) {
            throw e;
        } finally {
            storageService.deleteRecursive(tempDir);
        }
    }

    private Project insertProject(String name, String description, String sourceType, String repoUrl) {
        Project project = new Project();
        project.setName(name.trim());
        project.setDescription(description);
        project.setSourceType(sourceType);
        project.setRepoUrl(repoUrl);
        project.setStatus(ProjectStatus.CREATED.name());
        project.setStoragePath("pending");
        projectMapper.insert(project);
        return project;
    }

    private ProjectResp toResp(Project p) {
        return ProjectResp.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .sourceType(p.getSourceType())
                .status(p.getStatus())
                .storagePath(p.getStoragePath())
                .langStats(p.getLangStats())
                .locTotal(p.getLocTotal())
                .fileCount(p.getFileCount())
                .frameworkTags(p.getFrameworkTags())
                .lastAnalyzedAt(p.getLastAnalyzedAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
