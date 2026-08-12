package com.evocode.dto.analysis;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 报告历史项（P9c，06 §3.7 扩展）。
 * 聚合某项目 SUCCEEDED 且含 report_json 的分析摘要，供前端趋势折线与两期对比。
 *
 * @param healthScore report_json 内 healthScore，数值防御后可为 null
 * @param dimensions  维度摘要（key/score/stars），来源 report_json.dimensions
 * @param risks       风险摘要（level/title），来源 report_json.risks，供两期 diff
 */
public record ReportHistoryResp(Long analysisId, OffsetDateTime createdAt, Integer healthScore,
                                String level, List<Dimension> dimensions, List<Risk> risks,
                                String source) {

    /** 维度摘要（key/score/stars）。 */
    public record Dimension(String key, Integer score, Integer stars) {
    }

    /** 风险摘要（level/title）。 */
    public record Risk(String level, String title) {
    }
}
