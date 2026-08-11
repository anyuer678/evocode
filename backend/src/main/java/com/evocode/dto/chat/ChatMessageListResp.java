package com.evocode.dto.chat;

import java.util.List;

/**
 * 消息列表响应（分页，§6 统一 {total, page, size, items}）。
 */
public record ChatMessageListResp(
        Long total,
        Integer page,
        Integer size,
        List<ChatMessageResp> items
) {
}
