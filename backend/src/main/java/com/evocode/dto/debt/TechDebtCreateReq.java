package com.evocode.dto.debt;

/**
 * 手动/AI 登记技术债请求（06 §3.12 扩展，TD-04）。
 *
 * @param source      MANUAL（前端手动登记）/ AI_DOCTOR（AI 医生会话确认）
 * @param title       必填，≤200
 * @param level       HIGH / MEDIUM / LOW（缺省 MEDIUM）
 * @param description 描述（可空）
 * @param suggestion  建议（可空）
 */
public record TechDebtCreateReq(String source, String title, String level,
                                String description, String suggestion) {
}
