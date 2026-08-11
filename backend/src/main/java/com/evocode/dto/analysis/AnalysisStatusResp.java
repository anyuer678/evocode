package com.evocode.dto.analysis;

/**
 * 分析状态轮询响应（docs/06-API契约.md §3.6，前端 2s 间隔）。
 */
public record AnalysisStatusResp(Long id, String status, Integer progress,
                                 String stage, String errorMessage) {
}
