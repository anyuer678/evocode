package com.evocode.service.project;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.entity.Analysis;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.ArchitectureEdge;
import com.evocode.entity.ArchitectureNode;
import com.evocode.entity.ChatMessage;
import com.evocode.entity.ChatSession;
import com.evocode.entity.CommitStat;
import com.evocode.entity.FileChangeStat;
import com.evocode.entity.FileNode;
import com.evocode.entity.GeneratedDoc;
import com.evocode.entity.Hotspot;
import com.evocode.entity.Project;
import com.evocode.entity.QualityIssue;
import com.evocode.entity.TechDebt;
import com.evocode.enums.AnalysisStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;

/**
 * 项目删除级联（06 §3.4 删除时序）：15 张关联表清库 + 事务提交后磁盘目录清理。
 * 从 ProjectServiceImpl 抽出——原 20 个构造依赖中 14 个仅删除路径使用。
 * 事务边界：默认 REQUIRED 传播；整个级联必须留在同一事务内（禁止 REQUIRES_NEW）。
 */
@Service
public class ProjectDeleteService {

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
    private final ProjectStorageService storageService;

    public ProjectDeleteService(ProjectMapper projectMapper, AnalysisMapper analysisMapper,
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
                                GeneratedDocMapper generatedDocMapper,
                                AnalysisReportMapper analysisReportMapper,
                                ProjectStorageService storageService) {
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
        this.storageService = storageService;
    }

    @Transactional
    public void delete(Long id) {
        Project project = getById(id);
        // 06 §3.4：RUNNING 任务 → CANCELLED（快扫线程会检查取消）
        analysisMapper.update(null, new UpdateWrapper<Analysis>()
                .eq("project_id", id)
                .eq("status", AnalysisStatus.RUNNING.name())
                .set("status", AnalysisStatus.CANCELLED.name()));
        fileNodeMapper.delete(new QueryWrapper<FileNode>().eq("project_id", id));
        qualityIssueMapper.delete(new QueryWrapper<QualityIssue>().eq("project_id", id));
        architectureNodeMapper.delete(new QueryWrapper<ArchitectureNode>().eq("project_id", id));
        architectureEdgeMapper.delete(new QueryWrapper<ArchitectureEdge>().eq("project_id", id));
        archViolationMapper.delete(new QueryWrapper<ArchViolation>().eq("project_id", id));
        commitStatMapper.delete(new QueryWrapper<CommitStat>().eq("project_id", id));
        fileChangeStatMapper.delete(new QueryWrapper<FileChangeStat>().eq("project_id", id));
        hotspotMapper.delete(new QueryWrapper<Hotspot>().eq("project_id", id));
        // P6/P7 新表级联（06 §3.4 删除时序；chat_message 先于 chat_session 逻辑删）
        chatMessageMapper.delete(new QueryWrapper<ChatMessage>()
                .inSql("session_id",
                        "SELECT id FROM chat_session WHERE project_id = " + id));
        chatSessionMapper.delete(new QueryWrapper<ChatSession>().eq("project_id", id));
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
                    storageService.deleteRecursive(storage);
                }
            });
        } else {
            storageService.deleteRecursive(storage);
        }
    }

    private Project getById(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        return project;
    }
}
