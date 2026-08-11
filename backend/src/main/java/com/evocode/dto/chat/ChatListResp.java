package com.evocode.dto.chat;

import java.util.List;

/**
 * 会话列表响应（06 §3.15：data = {total, items}，不分页）。
 */
public record ChatListResp(Long total, List<ChatSessionResp> items) {
}
