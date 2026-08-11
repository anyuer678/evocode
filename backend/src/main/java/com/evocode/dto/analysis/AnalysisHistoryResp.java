package com.evocode.dto.analysis;

import java.time.OffsetDateTime;

/**
 * 分析历史项（docs/06-API契约.md §3.6）。
 *
 * @param source      report_source：LLM / RULES，P2a 阶段无报告时为 null
 * @param healthScore report_json 内 healthScore，无报告时为 null
 */
public record AnalysisHistoryResp(Long id, String type, String status, Integer progress,
                                  String stage, String errorMessage,
                                  OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                  String source, Integer healthScore) {
}
