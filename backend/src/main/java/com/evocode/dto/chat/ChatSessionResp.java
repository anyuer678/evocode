package com.evocode.dto.chat;

import java.time.OffsetDateTime;

/**
 * 会话列表项（06 §3.15）。
 */
public record ChatSessionResp(
        Long id,
        String title,
        Integer messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime lastMessageAt
) {
}
