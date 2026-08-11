package com.evocode.dto.analysis;

import java.time.OffsetDateTime;

/** 详情页最近分析摘要（06 §3.3 latestAnalysis；P1 无报告时仅回状态）。 */
public record LatestAnalysisResp(Long id, String status, String stage, Integer progress,
                                 OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}
