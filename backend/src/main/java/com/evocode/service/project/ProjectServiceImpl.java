package com.evocode.service.project;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.config.EvocodeProperties;
import com.evocode.dto.analysis.LatestAnalysisResp;
import com.evocode.dto.project.ProjectDetailResp;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.FileNode;
import com.evocode.entity.Project;
import com.evocode.entity.QualityIssue;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.enums.ProjectSourceType;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ArchViolationMapper;
import com.evocode.mapper.ArchitectureEdgeMapper;
import com.evocode.mapper.ArchitectureNodeMapper;
import com.evocode.mapper.FileNodeMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import com.evocode.service.analysis.QuickScanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 项目 CRUD + 删除级联（06 §3.1~3.4；AD-6：磁盘存代码，DB 存元数据）。
 */
@Slf4j
@Service
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final AnalysisMapper analysisMapper;
    private final FileNodeMapper fileNodeMapper;
    private final QualityIssueMapper qualityIssueMapper;
    private final ArchitectureNodeMapper architectureNodeMapper;
    private final ArchitectureEdgeMapper architectureEdgeMapper;
    private final ArchViolationMapper archViolationMapper;
    private final UploadService uploadService;
    private final GitCloneService gitCloneService;
    private final QuickScanService quickScanService;
    private final EvocodeProperties props;

    public ProjectServiceImpl(ProjectMapper projectMapper, AnalysisMapper analysisMapper,
                              FileNodeMapper fileNodeMapper, QualityIssueMapper qualityIssueMapper,
                              ArchitectureNodeMapper architectureNodeMapper,
                              ArchitectureEdgeMapper architectureEdgeMapper,
                              ArchViolationMapper archViolationMapper,
                              UploadService uploadService, GitCloneService gitCloneService,
                              QuickScanService quickScanService, EvocodeProperties props) {
        this.projectMapper = projectMapper;
        this.analysisMapper = analysisMapper;
        this.fileNodeMapper = fileNodeMapper;
        this.qualityIssueMapper = qualityIssueMapper;
        this.architectureNodeMapper = architectureNodeMapper;
        this.architectureEdgeMapper = architectureEdgeMapper;
        this.archViolationMapper = archViolationMapper;
        this.uploadService = uploadService;
        this.gitCloneService = gitCloneService;
        this.quickScanService = quickScanService;
        this.props = props;
    }

    @Override
    public ProjectResp createFromZip(String name, String description, MultipartFile file) {
        Path tempDir = createTempDir();
        try {
            Path root = uploadService.extractZip(tempDir, file);
            Project project = insertProject(name, description, ProjectSourceType.ZIP.name(), null);
            try {
                moveDir(root, storagePathOf(project.getId()));
            } catch (IOException e) {
                projectMapper.deleteById(project.getId());
                throw new BusinessException(ErrorCode.FILE_ILLEGAL, "代码移入存储失败：" + e.getMessage());
            }
            project.setStoragePath(relStoragePath(project.getId()));
            projectMapper.updateById(project);
            quickScanService.quickScan(project);
            return toResp(project);
        } catch (BusinessException e) {
            throw e;
        } finally {
            deleteRecursive(tempDir);
        }
    }

    @Override
    public ProjectResp createFromGit(String name, String description, String repoUrl, Integer cloneDepth) {
        int depth = cloneDepth == null ? 1 : cloneDepth;
        if (depth < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "cloneDepth 非法（0=全量，>0 为 depth）");
        }
        Path tempDir = createTempDir();
        try {
            Path repoDir = tempDir.resolve("repo");
            gitCloneService.clone(repoUrl, depth, repoDir);
            Project project = insertProject(name, description, ProjectSourceType.GIT.name(), repoUrl.trim());
            try {
                moveDir(repoDir, storagePathOf(project.getId()));
            } catch (IOException e) {
                projectMapper.deleteById(project.getId());
                throw new BusinessException(ErrorCode.GIT_CLONE_FAILED, "代码移入存储失败：" + e.getMessage());
            }
            project.setStoragePath(relStoragePath(project.getId()));
            projectMapper.updateById(project);
            quickScanService.quickScan(project);
            return toResp(project);
        } catch (BusinessException e) {
            throw e;
        } finally {
            deleteRecursive(tempDir);
        }
    }

    @Override
    public IPage<ProjectSummaryResp> list(int page, int size, String keyword, String language,
                                          String status, String sort, String order) {
        String orderColumn = switch (sort == null ? "createdAt" : sort) {
            case "createdAt" -> "p.created_at";
            case "lastAnalyzedAt" -> "p.last_analyzed_at";
            case "locTotal" -> "p.loc_total";
            case "name" -> "p.name";
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "sort 不在白名单");
        };
        String orderDir = "desc".equalsIgnoreCase(order) ? "desc" : "asc";
        if (order != null && !"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "order 仅支持 asc/desc");
        }
        if (status != null && !status.isBlank() && ProjectStatus.valueOfSafe(status) == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "status 非法");
        }
        return projectMapper.selectSummaryPage(new Page<>(page, size), keyword, language, status, orderColumn, orderDir);
    }

    @Override
    public ProjectDetailResp detail(Long id) {
        Project project = getById(id);
        Analysis latest = analysisMapper.selectOne(new QueryWrapper<Analysis>()
                .eq("project_id", id)
                .orderByDesc("id")
                .last("LIMIT 1"));
        LatestAnalysisResp latestResp = latest == null ? null
                : new LatestAnalysisResp(latest.getId(), latest.getStatus(), latest.getStage(),
                        latest.getProgress(), latest.getStartedAt(), latest.getFinishedAt());
        return ProjectDetailResp.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .sourceType(project.getSourceType())
                .repoUrl(project.getRepoUrl())
                .status(project.getStatus())
                .langStats(project.getLangStats())
                .frameworkTags(project.getFrameworkTags())
                .locTotal(project.getLocTotal())
                .fileCount(project.getFileCount())
                .ignoredCount(project.getIgnoredCount())
                .lastAnalyzedAt(project.getLastAnalyzedAt())
                .latestAnalysis(latestResp)
                .createdAt(project.getCreatedAt())
                .build();
    }

    @Override
    public void delete(Long id) {
        Project project = getById(id);
        // 06 §3.4：RUNNING 任务 → CANCELLED（快扫线程会检查取消）
        analysisMapper.update(null, new UpdateWrapper<Analysis>()
                .eq("project_id", id)
                .eq("status", AnalysisStatus.RUNNING.name())
                .set("status", AnalysisStatus.CANCELLED.name()));
        fileNodeMapper.delete(new QueryWrapper<com.evocode.entity.FileNode>().eq("project_id", id));
        qualityIssueMapper.delete(new QueryWrapper<QualityIssue>().eq("project_id", id));
        architectureNodeMapper.delete(new QueryWrapper<com.evocode.entity.ArchitectureNode>().eq("project_id", id));
        architectureEdgeMapper.delete(new QueryWrapper<com.evocode.entity.ArchitectureEdge>().eq("project_id", id));
        archViolationMapper.delete(new QueryWrapper<ArchViolation>().eq("project_id", id));
        analysisMapper.delete(new QueryWrapper<Analysis>().eq("project_id", id));
        projectMapper.deleteById(id);
        deleteRecursive(Path.of(project.getStoragePath()));
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

    private Project getById(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        return project;
    }

    private Path storagePathOf(Long projectId) {
        return Path.of(props.getDataDir(), "projects", String.valueOf(projectId));
    }

    private String relStoragePath(Long projectId) {
        return "data/projects/" + projectId;
    }

    private Path createTempDir() {
        try {
            return Files.createTempDirectory("evocode-upload-");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_ILLEGAL, "临时目录创建失败：" + e.getMessage());
        }
    }

    /** 同盘 move；跨盘回退 copy+delete。 */
    private void moveDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            copyRecursive(src, dst);
            deleteRecursive(src);
        }
    }

    private void copyRecursive(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : stream.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteRecursive(Path root) {
        try {
            if (root != null && Files.exists(root)) {
                Files.walk(root)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("目录清理失败: {}", root, e);
        }
    }

    private ProjectResp toResp(Project p) {
        return ProjectResp.builder()
                .id(p.getId())
                .name(p.getName())
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
