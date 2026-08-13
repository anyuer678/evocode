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
import com.evocode.dto.analysis.ReportHistoryResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.AnalysisReport;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.AnalysisType;
import com.evocode.enums.Stage;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.report.ReportStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private final ReportStorageService reportStorageService;

    public AnalysisServiceImpl(AnalysisMapper analysisMapper, ProjectMapper projectMapper,
                               AnalysisRunner analysisRunner, ReportStorageService reportStorageService) {
        this.analysisMapper = analysisMapper;
        this.projectMapper = projectMapper;
        this.analysisRunner = analysisRunner;
        this.reportStorageService = reportStorageService;
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
        if (analysis == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "该分析不存在");
        }
        AnalysisReport r = reportStorageService.getByAnalysisId(analysisId);
        if (r == null || r.getReportJson() == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "该分析暂无报告");
        }
        return new ReportDetailResp(analysis.getId(),
                analysis.getFinishedAt() != null ? analysis.getFinishedAt() : analysis.getCreatedAt(),
                analysis.getReportSource(), analysis.getPromptVersion(), r.getReportJson());
    }

    @Override
    public AnalysisStatusResp regenerate(Long analysisId) {
        Analysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null || reportStorageService.getByAnalysisId(analysisId) == null) {
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

    /**
     * P9c：报告历史（SUCCEEDED + report_json 非空，按 id 倒序，limit 1~20）。
     * 从 report_json 提取 healthScore/level/dimensions/risks 摘要；数值防御同 P8 列表。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<ReportHistoryResp> reportHistory(Long projectId, int limit) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        List<Analysis> list = analysisMapper.selectList(new QueryWrapper<Analysis>()
                .eq("project_id", projectId)
                .eq("status", AnalysisStatus.SUCCEEDED.name())
                .orderByDesc("id")
                .last("LIMIT " + Math.max(1, Math.min(limit, 20))));
        List<ReportHistoryResp> result = new ArrayList<>(list.size());
        for (Analysis a : list) {
            AnalysisReport r = reportStorageService.getByAnalysisId(a.getId());
            if (r != null) {
                result.add(toReportHistoryResp(a, r));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ReportHistoryResp toReportHistoryResp(Analysis a, AnalysisReport r) {
        Map<String, Object> report = r.getReportJson();
        Integer healthScore = r.getHealthScore();
        String level = r.getLevel();
        List<ReportHistoryResp.Dimension> dims = List.of();
        List<ReportHistoryResp.Risk> risks = List.of();
        if (report != null) {
            Object dimsObj = report.get("dimensions");
            if (dimsObj instanceof List<?> dimList) {
                dims = new ArrayList<>(dimList.size());
                for (Object o : dimList) {
                    if (o instanceof Map<?, ?> m) {
                        Object key = m.get("key");
                        Object s = m.get("score");
                        Object stars = m.get("stars");
                        dims.add(new ReportHistoryResp.Dimension(
                                key == null ? null : key.toString(),
                                s instanceof Number sn ? sn.intValue() : null,
                                stars instanceof Number st ? st.intValue() : null));
                    }
                }
            }
            Object risksObj = report.get("risks");
            if (risksObj instanceof List<?> riskList) {
                risks = new ArrayList<>(riskList.size());
                for (Object o : riskList) {
                    if (o instanceof Map<?, ?> m) {
                        Object lvl = m.get("level");
                        Object title = m.get("title");
                        risks.add(new ReportHistoryResp.Risk(
                                lvl == null ? null : lvl.toString(),
                                title == null ? null : title.toString()));
                    }
                }
            }
        }
        OffsetDateTime createdAt = a.getFinishedAt() != null ? a.getFinishedAt() : a.getCreatedAt();
        return new ReportHistoryResp(a.getId(), createdAt, healthScore, level, dims, risks,
                a.getReportSource());
    }

    private AnalysisHistoryResp toHistoryResp(Analysis a) {
        AnalysisReport r = reportStorageService.getByAnalysisId(a.getId());
        Integer healthScore = r == null ? null : r.getHealthScore();
        return new AnalysisHistoryResp(a.getId(), a.getType(), a.getStatus(), a.getProgress(),
                a.getStage(), a.getErrorMessage(), a.getStartedAt(), a.getFinishedAt(),
                a.getReportSource(), healthScore);
    }
}
