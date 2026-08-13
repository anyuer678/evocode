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
import com.evocode.dto.project.ProjectUpdateReq;
import com.evocode.entity.Analysis;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.CommitStat;
import com.evocode.entity.FileChangeStat;
import com.evocode.entity.GeneratedDoc;
import com.evocode.entity.Hotspot;
import com.evocode.entity.TechDebt;
import com.evocode.entity.FileNode;
import com.evocode.entity.Project;
import com.evocode.entity.QualityIssue;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.enums.ProjectSourceType;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.AnalysisReportMapper;
import com.evocode.mapper.ArchViolationMapper;
import com.evocode.mapper.ArchitectureEdgeMapper;
import com.evocode.mapper.ArchitectureNodeMapper;
import com.evocode.mapper.ChatMessageMapper;
import com.evocode.mapper.ChatSessionMapper;
import com.evocode.mapper.CommitStatMapper;
import com.evocode.mapper.FileChangeStatMapper;
import com.evocode.mapper.GeneratedDocMapper;
import com.evocode.mapper.HotspotMapper;
import com.evocode.mapper.KnowledgeChunkMapper;
import com.evocode.mapper.TechDebtMapper;
import com.evocode.mapper.FileNodeMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import com.evocode.service.analysis.QuickScanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final CommitStatMapper commitStatMapper;
    private final FileChangeStatMapper fileChangeStatMapper;
    private final HotspotMapper hotspotMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final TechDebtMapper techDebtMapper;
    private final GeneratedDocMapper generatedDocMapper;
    private final AnalysisReportMapper analysisReportMapper;
    private final UploadService uploadService;
    private final GitCloneService gitCloneService;
    private final QuickScanService quickScanService;
    private final EvocodeProperties props;

    public ProjectServiceImpl(ProjectMapper projectMapper, AnalysisMapper analysisMapper,
                              FileNodeMapper fileNodeMapper, QualityIssueMapper qualityIssueMapper,
                              ArchitectureNodeMapper architectureNodeMapper,
                              ArchitectureEdgeMapper architectureEdgeMapper,
                              ArchViolationMapper archViolationMapper,
                              CommitStatMapper commitStatMapper,
                              FileChangeStatMapper fileChangeStatMapper,
                              HotspotMapper hotspotMapper,
                              ChatSessionMapper chatSessionMapper,
                              ChatMessageMapper chatMessageMapper,
                              KnowledgeChunkMapper knowledgeChunkMapper,
                              TechDebtMapper techDebtMapper,
                              GeneratedDocMapper generatedDocMapper, AnalysisReportMapper analysisReportMapper,
                              UploadService uploadService, GitCloneService gitCloneService,
                              QuickScanService quickScanService, EvocodeProperties props) {
        this.projectMapper = projectMapper;
        this.analysisMapper = analysisMapper;
        this.fileNodeMapper = fileNodeMapper;
        this.qualityIssueMapper = qualityIssueMapper;
        this.architectureNodeMapper = architectureNodeMapper;
        this.architectureEdgeMapper = architectureEdgeMapper;
        this.archViolationMapper = archViolationMapper;
        this.commitStatMapper = commitStatMapper;
        this.fileChangeStatMapper = fileChangeStatMapper;
        this.hotspotMapper = hotspotMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.techDebtMapper = techDebtMapper;
        this.generatedDocMapper = generatedDocMapper;
        this.analysisReportMapper = analysisReportMapper;
        this.uploadService = uploadService;
        this.gitCloneService = gitCloneService;
        this.quickScanService = quickScanService;
        this.props = props;
    }

    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
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
    @CacheEvict(cacheNames = "projectList", allEntries = true)
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
    @Cacheable(cacheNames = "projectList")
    public IPage<ProjectSummaryResp> list(int page, int size, String keyword, String language,
                                          String status, String sort, String order) {
        String orderColumn = switch (sort == null ? "createdAt" : sort) {
            case "createdAt" -> "p.created_at";
            case "lastAnalyzedAt" -> "p.last_analyzed_at";
            case "locTotal" -> "p.loc_total";
            case "name" -> "p.name";
            // SPI-6：healthScore 走 analysis_report.health_score 列（LATERAL JOIN）
            case "healthScore" -> "health_score";
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "sort 不在白名单");
        };
        // 审查修复：契约 §6「时间类默认 desc，其余 asc」——此前 order 缺省一律 asc，
        // createdAt/lastAnalyzedAt 默认应降序（最新在前）。sort 缺省等价于 createdAt。
        String orderDir;
        if (order == null || order.isBlank()) {
            orderDir = isTimeSort(sort == null ? "createdAt" : sort) ? "desc" : "asc";
        } else {
            orderDir = "desc".equalsIgnoreCase(order) ? "desc" : "asc";
        }
        // P9e 语义修复：healthScore 降序时 PG 默认 NULLS FIRST（无报告项目排最前）→
        // 显式 NULLS LAST 垫底。语法须为 `expr DESC NULLS LAST`（NULLS 在 ASC/DESC 之后）。
        if ("healthScore".equals(sort)) {
            orderDir = orderDir + " NULLS LAST";
        }
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

    @Transactional
    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
    public ProjectResp update(Long id, ProjectUpdateReq req) {
        if (req == null || req.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING,
                    "至少提供一个更新字段（name 或 description）");
        }
        String name = req.name();
        if (name != null && name.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "name 不能为空");
        }
        if (name != null && name.length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "name 长度不能超过 100");
        }
        Project project = getById(id);
        UpdateWrapper<Project> uw = new UpdateWrapper<Project>().eq("id", id);
        boolean changed = false;
        if (name != null && !name.equals(project.getName())) {
            uw.set("name", name);
            changed = true;
        }
        if (req.description() != null && !req.description().equals(project.getDescription())) {
            uw.set("description", req.description());
            changed = true;
        }
        if (changed) {
            projectMapper.update(null, uw);
        }
        return toResp(getById(id));
    }

    @Transactional
    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
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
        commitStatMapper.delete(new QueryWrapper<CommitStat>().eq("project_id", id));
        fileChangeStatMapper.delete(new QueryWrapper<FileChangeStat>().eq("project_id", id));
        hotspotMapper.delete(new QueryWrapper<Hotspot>().eq("project_id", id));
        // P6/P7 新表级联（06 §3.4 删除时序；chat_message 先于 chat_session 逻辑删）
        chatMessageMapper.delete(new QueryWrapper<com.evocode.entity.ChatMessage>()
                .inSql("session_id",
                        "SELECT id FROM chat_session WHERE project_id = " + id));
        chatSessionMapper.delete(new QueryWrapper<com.evocode.entity.ChatSession>().eq("project_id", id));
        knowledgeChunkMapper.deleteByProjectId(id);
        techDebtMapper.delete(new QueryWrapper<TechDebt>().eq("project_id", id));
        generatedDocMapper.delete(new QueryWrapper<GeneratedDoc>().eq("project_id", id));
        // 审查：SPI-6 拆表后 analysis 为逻辑删除（FK 不生效），须显式清理 analysis_report 孤儿行
        analysisReportMapper.deleteByProjectId(id);
        analysisMapper.delete(new QueryWrapper<Analysis>().eq("project_id", id));
        projectMapper.deleteById(id);
        // 审查修复：磁盘删除移出事务——事务提交成功后（afterCommit）再清理磁盘，
        // 避免事务回滚时磁盘目录已删而 DB 记录仍在（数据不一致）。
        // 无活动事务（单元测试直调）时同步删除。
        Path storage = Path.of(project.getStoragePath());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteRecursive(storage);
                }
            });
        } else {
            deleteRecursive(storage);
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
        // 审查修复：与 storagePathOf 完全一致（dataDir 感知）——此前硬编码 "data/projects/"，DB
        // 存的相对路径在 DATA_DIR 自定义后与磁盘实际路径脱节，FileController/ChatStreamer 等
        // 以 Path.of(storagePath) 解析会指向错误位置。
        return storagePathOf(projectId).toString();
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

    /** 契约 §6：时间类 sort（createdAt/lastAnalyzedAt）缺省 order 为 desc，其余 asc。 */
    private static boolean isTimeSort(String sort) {
        return "createdAt".equals(sort) || "lastAnalyzedAt".equals(sort);
    }
}
