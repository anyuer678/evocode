package com.evocode.dto.quality;

import java.util.Map;

/**
 * 质量指标聚合（docs/06-API契约.md §3.10）。
 * available=false 表示 Sonar 未启用；comparedWithLast 为预留（P5 演化期对比）。
 */
public record QualityMetricsResp(
        Integer bugs,
        Integer vulnerabilities,
        Integer codeSmells,
        Double duplicationRate,
        Double coverageRate,
        Double complexity,
        Boolean available,
        Map<String, Object> comparedWithLast) {
}
