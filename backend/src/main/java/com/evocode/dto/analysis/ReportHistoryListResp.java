package com.evocode.dto.analysis;

import java.util.List;

/**
 * 报告历史响应（P9c，06 §3.7 扩展）：data.items 包裹，与分页风格一致。
 */
public record ReportHistoryListResp(List<ReportHistoryResp> items) {
}
