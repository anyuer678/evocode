package com.evocode.dto.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息项（06 §3.15 / §4.2）。
 */
public record ChatMessageResp(
        Long id,
        String role,
        String content,
        List<Map<String, Object>> citations,
        OffsetDateTime createdAt
) {
}
