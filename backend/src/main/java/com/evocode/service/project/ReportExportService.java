package com.evocode.service.project;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 报告导出（06 §3.7 扩展，P9b）：把最新 SUCCEEDED 分析的 report_json
 * 渲染为纯字符串 Markdown（不依赖前端/模板引擎），供浏览器下载。
 */
@Service
public class ReportExportService {

    private static final Map<String, String> DIM_LABEL = Map.of(
            "quality", "质量",
            "structure", "结构",
            "dependency", "依赖",
            "scale", "规模");

    private final AnalysisMapper analysisMapper;
    private final ProjectMapper projectMapper;

    public ReportExportService(AnalysisMapper analysisMapper, ProjectMapper projectMapper) {
        this.analysisMapper = analysisMapper;
        this.projectMapper = projectMapper;
    }

    /** 最新 SUCCEEDED 且含 report_json 的分析 → Markdown 字符串。无报告 → 2001。 */
    @SuppressWarnings("unchecked")
    public String exportLatest(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在");
        }
        Analysis analysis = analysisMapper.selectOne(new QueryWrapper<Analysis>()
                .eq("project_id", projectId)
                .eq("status", AnalysisStatus.SUCCEEDED.name())
                .isNotNull("report_json")
                .orderByDesc("id")
                .last("LIMIT 1"));
        if (analysis == null || analysis.getReportJson() == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "该项目尚无成功分析报告");
        }
        Map<String, Object> r = analysis.getReportJson();
        StringBuilder sb = new StringBuilder(4096);
        String name = project.getName() == null ? "未知项目" : project.getName();
        sb.append("# EvoCode 体检报告 —— ").append(name).append("\n\n");
        OffsetDateTime gen = analysis.getFinishedAt() != null ? analysis.getFinishedAt()
                : analysis.getCreatedAt();
        sb.append("> 分析 ID：").append(analysis.getId())
                .append("　生成：").append(gen.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append("　来源：").append(analysis.getReportSource()).append("\n\n");
        appendScore(sb, r);
        appendDimensions(sb, r.get("dimensions"));
        appendRisks(sb, r.get("risks"));
        appendRecommendations(sb, r.get("recommendations"));
        return sb.toString();
    }

    private void appendScore(StringBuilder sb, Map<String, Object> r) {
        Object score = r.get("healthScore");
        Object level = r.get("level");
        Object summary = r.get("summary");
        if (score instanceof Number n) {
            sb.append("## 健康评分\n\n- **总分：").append(n.intValue()).append("/100**");
            if (level != null) {
                sb.append("（").append(level).append("）");
            }
            sb.append('\n');
            if (summary != null && !summary.toString().isBlank()) {
                sb.append("- 概述：").append(summary).append('\n');
            }
            sb.append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private void appendDimensions(StringBuilder sb, Object dims) {
        if (!(dims instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        sb.append("## 维度评分\n\n| 维度 | 得分 | 摘要 |\n|---|---|---|\n");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String key = String.valueOf(m.get("key"));
            String label = DIM_LABEL.getOrDefault(key, key);
            Object score = m.get("score");
            Object stars = m.get("stars");
            Object summary = m.get("summary");
            String scoreText = score instanceof Number n ? n.intValue() + "/100" : "-";
            String starText = stars instanceof Number st ? "★".repeat(Math.max(1, Math.min(5, st.intValue()))) : "";
            String sum = summary == null ? "" : summary.toString().replace('\n', ' ').trim();
            sb.append("| ").append(label).append(" | ").append(scoreText).append(' ')
                    .append(starText).append(" | ").append(sum).append(" |\n");
        }
        sb.append('\n');
    }

    @SuppressWarnings("unchecked")
    private void appendRisks(StringBuilder sb, Object risks) {
        if (!(risks instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        sb.append("## 风险项\n\n");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String level = String.valueOf(m.get("level"));
            String title = String.valueOf(m.get("title"));
            sb.append("### ").append(icon(level)).append(' ').append(level)
                    .append("：").append(title).append("\n\n");
            Object detail = m.get("detail");
            if (detail != null && !detail.toString().isBlank()) {
                sb.append("- 说明：").append(detail).append('\n');
            }
            Object suggestion = m.get("suggestion");
            if (suggestion != null && !suggestion.toString().isBlank()) {
                sb.append("- 建议：").append(suggestion).append('\n');
            }
            Object refs = m.get("references");
            if (refs instanceof List<?> rl && !rl.isEmpty()) {
                sb.append("- 引用：").append(String.join("、",
                        rl.stream().map(String::valueOf).toList())).append('\n');
            }
            sb.append('\n');
        }
    }

    @SuppressWarnings("unchecked")
    private void appendRecommendations(StringBuilder sb, Object recs) {
        if (!(recs instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        sb.append("## 改进建议\n\n");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String phase = String.valueOf(m.get("phase"));
            Object items = m.get("items");
            if (!(items instanceof List<?> il) || il.isEmpty()) {
                continue;
            }
            sb.append("### ").append(phase.isBlank() ? "后续" : phase).append("\n\n");
            for (Object item : il) {
                sb.append("- ").append(item).append('\n');
            }
            sb.append('\n');
        }
    }

    private String icon(String level) {
        return switch (level.toUpperCase()) {
            case "HIGH", "BLOCKER", "CRITICAL" -> "🔴";
            case "MEDIUM", "MAJOR" -> "🟠";
            default -> "🟡";
        };
    }
}
