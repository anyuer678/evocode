package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.PageResultResp;
import com.evocode.dto.analysis.AnalysisHistoryResp;
import com.evocode.dto.analysis.AnalysisResp;
import com.evocode.dto.analysis.AnalysisStatusResp;
import com.evocode.dto.analysis.ReportDetailResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.AnalysisType;
import com.evocode.enums.Stage;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 分析任务管理实现（docs/06-API契约.md §3.5/3.6）。
 * 发起即落 PENDING 记录，异步执行由 {@link AnalysisRunner} 承担。
 */
@Slf4j
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private final AnalysisMapper analysisMapper;
    private final ProjectMapper projectMapper;
    private final AnalysisRunner analysisRunner;

    public AnalysisServiceImpl(AnalysisMapper analysisMapper, ProjectMapper projectMapper,
                               AnalysisRunner analysisRunner) {
        this.analysisMapper = analysisMapper;
        this.projectMapper = projectMapper;
        this.analysisRunner = analysisRunner;
    }

    @Override
    public AnalysisResp create(Long projectId, String type) {
        if (projectId == null || projectId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "projectId 非法");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }

        // v0.1 仅支持 FULL；类型校验放在排他之前，尽早失败
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        if (!AnalysisType.FULL.name().equals(normalizedType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "v0.1 仅支持 FULL 分析");
        }

        Long running = analysisMapper.selectCount(new QueryWrapper<Analysis>()
                .eq("project_id", projectId)
                .eq("type", AnalysisType.FULL.name())
                .in("status", AnalysisStatus.PENDING.name(), AnalysisStatus.RUNNING.name()));
        if (running != null && running > 0) {
            throw new BusinessException(ErrorCode.ANALYSIS_BUSY, null);
        }

        Analysis analysis = new Analysis();
        analysis.setProjectId(projectId);
        analysis.setType(AnalysisType.FULL.name());
        analysis.setStatus(AnalysisStatus.PENDING.name());
        analysis.setProgress(0);
        analysis.setStage(Stage.QUEUED.name());
        analysisMapper.insert(analysis);

        // 跨 bean 调用，@Async 生效
        analysisRunner.run(analysis.getId());
        log.info("发起分析 analysisId={} projectId={}", analysis.getId(), projectId);

        return new AnalysisResp(analysis.getId(), projectId, analysis.getType(),
                analysis.getStatus(), analysis.getProgress(), analysis.getStage(), analysis.getCreatedAt());
    }

    @Override
    public PageResultResp<AnalysisHistoryResp> history(Long projectId, int page, int size) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        Page<Analysis> result = analysisMapper.selectPage(new Page<>(page, size),
                new QueryWrapper<Analysis>()
                        .eq("project_id", projectId)
                        .orderByDesc("id"));
        IPage<AnalysisHistoryResp> resp = result.convert(this::toHistoryResp);
        PageResultResp<AnalysisHistoryResp> paged = PageResultResp.of(resp);
        return paged;
    }

    @Override
    public AnalysisStatusResp status(Long analysisId) {
        Analysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        return new AnalysisStatusResp(analysis.getId(), analysis.getStatus(),
                analysis.getProgress(), analysis.getStage(), analysis.getErrorMessage());
    }

    @Override
    public ReportDetailResp report(Long analysisId) {
        Analysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null || analysis.getReportJson() == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "该分析不存在或无报告");
        }
        return new ReportDetailResp(analysis.getId(),
                analysis.getFinishedAt() != null ? analysis.getFinishedAt() : analysis.getCreatedAt(),
                analysis.getReportSource(), analysis.getPromptVersion(), analysis.getReportJson());
    }

    @Override
    public AnalysisStatusResp regenerate(Long analysisId) {
        Analysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null || analysis.getReportJson() == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "该分析不存在或无报告");
        }
        if (AnalysisStatus.RUNNING.name().equals(analysis.getStatus())) {
            throw new BusinessException(ErrorCode.REPORT_REGENERATING, null);
        }
        // 状态转移：SUCCEEDED → RUNNING(REPORT,75)，异步完成后覆盖报告
        analysis.setStatus(AnalysisStatus.RUNNING.name());
        analysis.setStage(Stage.REPORT.name());
        analysis.setProgress(75);
        analysisMapper.updateById(analysis);

        analysisRunner.regenerateReport(analysisId);
        return new AnalysisStatusResp(analysisId, AnalysisStatus.RUNNING.name(), 75,
                Stage.REPORT.name(), null);
    }

    private AnalysisHistoryResp toHistoryResp(Analysis a) {
        Integer healthScore = null;
        Map<String, Object> report = a.getReportJson();
        if (report != null && report.get("healthScore") instanceof Number n) {
            healthScore = n.intValue();
        }
        return new AnalysisHistoryResp(a.getId(), a.getType(), a.getStatus(), a.getProgress(),
                a.getStage(), a.getErrorMessage(), a.getStartedAt(), a.getFinishedAt(),
                a.getReportSource(), healthScore);
    }
}
