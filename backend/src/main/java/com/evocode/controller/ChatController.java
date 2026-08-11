package com.evocode.controller;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.Result;
import com.evocode.dto.chat.ChatCreateResp;
import com.evocode.dto.chat.ChatListResp;
import com.evocode.dto.chat.ChatMessageListResp;
import com.evocode.dto.chat.ChatSendReq;
import com.evocode.service.chat.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 医生会话/消息（06 §3.15 / §4）。
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/projects/{projectId}/chats")
    public Result<ChatListResp> listSessions(@PathVariable Long projectId) {
        return Result.ok(chatService.listSessions(projectId));
    }

    @PostMapping("/projects/{projectId}/chats")
    public ResponseEntity<Result<ChatCreateResp>> createSession(@PathVariable Long projectId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.ok(chatService.createSession(projectId)));
    }

    @DeleteMapping("/chats/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id);
        return Result.ok(null);
    }

    @GetMapping("/chats/{id}/messages")
    public Result<ChatMessageListResp> listMessages(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID,
                    "分页参数非法");
        }
        return Result.ok(chatService.listMessages(id, page, size));
    }

    /**
     * 发消息（06 §4.1）：SSE 流式响应。同步段校验 + 落库 user 消息，
     * 流式透传在 chatExecutor 上执行；连接保持至 done / error / 180s 超时。
     */
    @PostMapping(value = "/chats/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable Long id, @RequestBody ChatSendReq req,
                                  jakarta.servlet.http.HttpServletResponse response) {
        String content = req.content();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "消息内容不能为空");
        }
        if (content.length() > 2000) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "消息过长（≤2000 字符）");
        }
        response.setHeader("X-Accel-Buffering", "no"); // 契约 §4.3：禁用中间层缓冲
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            chatService.cancelStream(id); // 审查 M5：中断 analyzer 流释放线程
            emitter.complete();
        });
        emitter.onCompletion(() -> chatService.cancelStream(id));
        chatService.sendMessage(id, content, req.fileRef(), emitter);
        return emitter;
    }
}
