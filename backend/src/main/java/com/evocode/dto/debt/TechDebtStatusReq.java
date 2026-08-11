package com.evocode.dto.debt;

/**
 * 状态更新请求（06 §3.12）。DONE 必填 resolveNote；WONTFIX 必填 wonfixReason。
 */
public record TechDebtStatusReq(String status, String resolveNote, String wonfixReason) {
}
