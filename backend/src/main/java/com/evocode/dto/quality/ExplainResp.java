package com.evocode.dto.quality;

/**
 * issue 解释结果（docs/06-API契约.md §3.10：POST /api/v1/quality-issues/{id}/explain）。
 * aiStatus 回填后的状态（DONE=解释完成 / FAILED=解释失败）。
 */
public record ExplainResp(
        Long id,
        String aiStatus) {
}
