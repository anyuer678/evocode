package com.evocode.dto.chat;

import java.time.OffsetDateTime;

/**
 * 建会话响应（06 §3.15）。
 */
public record ChatCreateResp(
        Long id,
        String title,
        Integer messageCount,
        OffsetDateTime createdAt
) {
}
