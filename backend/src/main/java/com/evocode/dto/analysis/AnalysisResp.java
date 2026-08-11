package com.evocode.dto.analysis;

import java.time.OffsetDateTime;

/**
 * 发起分析响应（docs/06-API契约.md §3.5，202）。
 */
public record AnalysisResp(Long id, Long projectId, String type, String status,
                           Integer progress, String stage, OffsetDateTime createdAt) {
}
