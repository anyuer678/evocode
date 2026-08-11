package com.evocode.service.chat;

import com.evocode.dto.chat.ChatCreateResp;
import com.evocode.dto.chat.ChatListResp;
import com.evocode.dto.chat.ChatMessageListResp;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 医生会话/消息（06 §3.15 / §4）。
 */
public interface ChatService {

    /** 会话列表（06 §3.15：data = {total, items}，按 lastMessageAt desc）。 */
    ChatListResp listSessions(Long projectId);

    /** 建会话（title 占位"新会话"，首条消息后自动生成）。 */
    ChatCreateResp createSession(Long projectId);

    /** 删会话（级联删除全部消息，逻辑删）。 */
    void deleteSession(Long id);

    /** 消息列表（分页，按 id asc）。 */
    ChatMessageListResp listMessages(Long sessionId, int page, int size);

    /**
     * 发消息（06 §4.1）：校验/防重复（2007）/落库 user 消息 → 异步流式（SSE）。
     * 同步段只做校验与落库；流式透传由 ChatStreamer 在 chatExecutor 上执行。
     */
    void sendMessage(Long sessionId, String content, String fileRef, SseEmitter emitter);
}
