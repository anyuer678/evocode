package com.evocode.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.chat.ChatCreateResp;
import com.evocode.dto.chat.ChatListResp;
import com.evocode.dto.chat.ChatMessageListResp;
import com.evocode.dto.chat.ChatMessageResp;
import com.evocode.dto.chat.ChatSessionResp;
import com.evocode.entity.ChatMessage;
import com.evocode.entity.ChatSession;
import com.evocode.entity.Project;
import com.evocode.mapper.ChatMessageMapper;
import com.evocode.mapper.ChatSessionMapper;
import com.evocode.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 会话/消息（06 §3.15 / §4）。校验 + 落库；SSE 透传委托 ChatStreamer。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int TITLE_MAX_LEN = 20;
    private static final int DUP_WINDOW_SECONDS = 2;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ProjectMapper projectMapper;
    private final ChatStreamer chatStreamer;

    public ChatServiceImpl(ChatSessionMapper chatSessionMapper,
                           ChatMessageMapper chatMessageMapper,
                           ProjectMapper projectMapper,
                           ChatStreamer chatStreamer) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.projectMapper = projectMapper;
        this.chatStreamer = chatStreamer;
    }

    @Override
    public ChatListResp listSessions(Long projectId) {
        requireProject(projectId);
        List<ChatSession> sessions = chatSessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getProjectId, projectId)
                        .orderByDesc(ChatSession::getId));
        List<ChatSessionResp> items = new ArrayList<>(sessions.size());
        for (ChatSession s : sessions) {
            Long count = chatMessageMapper.selectCount(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, s.getId()));
            ChatMessage last = chatMessageMapper.selectOne(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, s.getId())
                            .orderByDesc(ChatMessage::getId)
                            .last("LIMIT 1"));
            items.add(new ChatSessionResp(s.getId(), s.getTitle(), count.intValue(),
                    s.getCreatedAt(), last == null ? null : last.getCreatedAt()));
        }
        items.sort(Comparator.comparing(ChatSessionResp::lastMessageAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new ChatListResp((long) items.size(), items);
    }

    @Override
    public ChatCreateResp createSession(Long projectId) {
        requireProject(projectId);
        ChatSession session = new ChatSession();
        session.setProjectId(projectId);
        session.setTitle(DEFAULT_TITLE);
        chatSessionMapper.insert(session);
        return new ChatCreateResp(session.getId(), session.getTitle(), 0, session.getCreatedAt());
    }

    @Override
    @Transactional
    public void deleteSession(Long id) {
        ChatSession session = requireSession(id);
        chatSessionMapper.deleteById(session.getId()); // 逻辑删
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, session.getId())); // 逻辑删
    }

    @Override
    public ChatMessageListResp listMessages(Long sessionId, int page, int size) {
        requireSession(sessionId);
        IPage<ChatMessage> result = chatMessageMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));
        List<ChatMessageResp> items = result.getRecords().stream()
                .map(m -> new ChatMessageResp(m.getId(), m.getRole(), m.getContent(),
                        m.getCitations(), m.getCreatedAt()))
                .toList();
        return new ChatMessageListResp(result.getTotal(), page, size, items);
    }

    @Override
    public void sendMessage(Long sessionId, String content, String fileRef, SseEmitter emitter) {
        ChatSession session = requireSession(sessionId);
        requireProject(session.getProjectId());
        ChatMessage lastUser = chatMessageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .eq(ChatMessage::getRole, "USER")
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT 1"));
        if (lastUser != null && content.equals(lastUser.getContent())
                && lastUser.getCreatedAt() != null
                && lastUser.getCreatedAt().isAfter(OffsetDateTime.now().minusSeconds(DUP_WINDOW_SECONDS))) {
            throw new BusinessException(ErrorCode.CHAT_TOO_FREQUENT,
                    "发送过于频繁，请稍后再试");
        }
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(content);
        chatMessageMapper.insert(userMsg);
        if (DEFAULT_TITLE.equals(session.getTitle())) {
            session.setTitle(truncateTitle(content));
            chatSessionMapper.updateById(session);
        }
        chatStreamer.stream(session, content, fileRef, userMsg.getId(), emitter);
    }

    @Override
    public void cancelStream(Long sessionId) {
        chatStreamer.cancelStream(sessionId);
    }

    private ChatSession requireSession(Long id) {
        ChatSession session = chatSessionMapper.selectById(id);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private Project requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private static String truncateTitle(String content) {
        if (content.length() <= TITLE_MAX_LEN) {
            return content;
        }
        return content.substring(0, TITLE_MAX_LEN);
    }
}
