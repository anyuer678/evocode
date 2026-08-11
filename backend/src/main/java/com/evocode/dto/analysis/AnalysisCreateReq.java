package com.evocode.dto.analysis;

/**
 * 发起分析请求（docs/06-API契约.md §3.5）：{"type":"FULL"}。
 */
public record AnalysisCreateReq(String type) {
}
