package com.evocode.service.quality;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.quality.QualityIssueResp;
import com.evocode.dto.quality.QualityIssuesResp;
import com.evocode.dto.quality.QualityMetricsResp;
import com.evocode.entity.QualityIssue;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 质量 issues 查询实现。metrics 由 issue 记录现场聚合；
 * available = 该项目存在 Sonar 扫描记录（有 issue 数据即视为已接入）。
 */
@Service
public class QualityIssueServiceImpl implements QualityIssueService {

    private final QualityIssueMapper qualityIssueMapper;
    private final ProjectMapper projectMapper;

    public QualityIssueServiceImpl(QualityIssueMapper qualityIssueMapper, ProjectMapper projectMapper) {
        this.qualityIssueMapper = qualityIssueMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    public QualityIssuesResp query(Long projectId, String severity, String kind, String status,
                                   int page, int size) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }

        // count 查询不能带 ORDER BY（PG 下 ORDER BY 列须在 GROUP BY/结果集中）
        LambdaQueryWrapper<QualityIssue> countQw = applyFilters(
                new LambdaQueryWrapper<QualityIssue>(), projectId, severity, kind, status);
        long total = qualityIssueMapper.selectCount(countQw);

        LambdaQueryWrapper<QualityIssue> pageQw = applyFilters(
                new LambdaQueryWrapper<QualityIssue>(), projectId, severity, kind, status)
                .orderByDesc(QualityIssue::getId);
        Page<QualityIssue> result = qualityIssueMapper.selectPage(new Page<>(page, size), pageQw);
        List<QualityIssueResp> items = result.getRecords().stream()
                .map(this::toResp)
                .toList();
        return new QualityIssuesResp(aggregateMetrics(projectId, total), total, items);
    }

    private LambdaQueryWrapper<QualityIssue> applyFilters(LambdaQueryWrapper<QualityIssue> qw,
                                                          Long projectId, String severity,
                                                          String kind, String status) {
        qw.eq(QualityIssue::getProjectId, projectId);
        if (severity != null && !severity.isBlank()) {
            qw.eq(QualityIssue::getSeverity, severity.trim().toUpperCase());
        }
        if (kind != null && !kind.isBlank()) {
            qw.eq(QualityIssue::getKind, kind.trim().toUpperCase());
        }
        if (status != null && !status.isBlank()) {
            qw.eq(QualityIssue::getStatus, status.trim().toUpperCase());
        }
        return qw;
    }

    /** 聚合指标：按 kind 计数；available = 存在 Sonar 记录（有 issue 数据）。 */
    private QualityMetricsResp aggregateMetrics(Long projectId, long total) {
        boolean available = total > 0;
        if (!available) {
            // 0 issue 且 Sonar 已接入会误判为未启用（v0.2 记录在案，见 devlog）
            return new QualityMetricsResp(0, 0, 0, null, null, null, false, null);
        }
        List<QualityIssue> all = qualityIssueMapper.selectList(
                new LambdaQueryWrapper<QualityIssue>().eq(QualityIssue::getProjectId, projectId));
        int bugs = 0;
        int vulns = 0;
        int smells = 0;
        for (QualityIssue i : all) {
            switch (i.getKind() == null ? "" : i.getKind()) {
                case "BUG" -> bugs++;
                case "VULNERABILITY" -> vulns++;
                default -> smells++;
            }
        }
        return new QualityMetricsResp(bugs, vulns, smells, null, null, null, true, null);
    }

    private QualityIssueResp toResp(QualityIssue i) {
        return new QualityIssueResp(i.getId(), i.getSeverity(), i.getKind(), i.getRuleKey(),
                i.getFilePath(), i.getLine(), i.getMessage(), i.getAiExplanation(),
                i.getAiSuggestion(), i.getAiStatus(), i.getStatus());
    }
}
