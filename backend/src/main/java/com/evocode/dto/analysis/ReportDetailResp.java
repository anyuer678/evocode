package com.evocode.dto.analysis;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 报告详情（docs/06-API契约.md §3.7）。
 */
public record ReportDetailResp(Long analysisId, OffsetDateTime generatedAt, String source,
                               String promptVersion, Map<String, Object> report) {
}
