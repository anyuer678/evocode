package com.evocode.dto.quality;

import java.util.List;

/**
 * 质量 issues 查询结果（docs/06-API契约.md §3.10）。
 */
public record QualityIssuesResp(
        QualityMetricsResp metrics,
        long total,
        List<QualityIssueResp> items) {
}
