package com.evocode.dto.quality;

/**
 * 质量 issue 项（docs/06-API契约.md §3.10）。ai_* 字段 P3d 解释后回填。
 */
public record QualityIssueResp(
        Long id,
        String severity,
        String kind,
        String ruleKey,
        String filePath,
        Integer line,
        String message,
        String aiExplanation,
        String aiSuggestion,
        String aiStatus,
        String status) {
}
