package com.evocode.service.project;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.analysis.LatestAnalysisResp;
import com.evocode.dto.project.ProjectDetailResp;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.dto.project.ProjectUpdateReq;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.ProjectStatus;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目查询与轻量写（06 §3.2/3.3）：分页列表、详情、PATCH 更新。
 * 从 ProjectServiceImpl 抽出——list/update 各仅依赖 projectMapper，detail 额外读最新 analysis。
 * 事务边界：update 默认 REQUIRED（由门面或本服务开启均可）。
 */
@Service
public class ProjectQueryService {

    private final ProjectMapper projectMapper;
    private final AnalysisMapper analysisMapper;

    public ProjectQueryService(ProjectMapper projectMapper, AnalysisMapper analysisMapper) {
        this.projectMapper = projectMapper;
        this.analysisMapper = analysisMapper;
    }

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

    private Project getById(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
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

    /** 契约 §6：时间类 sort（createdAt/lastAnalyzedAt）缺省 order 为 desc，其余 asc。 */
    private static boolean isTimeSort(String sort) {
        return "createdAt".equals(sort) || "lastAnalyzedAt".equals(sort);
    }
}
