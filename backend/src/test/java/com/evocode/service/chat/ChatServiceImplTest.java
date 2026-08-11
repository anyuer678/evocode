package com.evocode.service.chat;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.chat.ChatCreateResp;
import com.evocode.dto.chat.ChatListResp;
import com.evocode.dto.chat.ChatMessageListResp;
import com.evocode.entity.ChatMessage;
import com.evocode.entity.ChatSession;
import com.evocode.entity.Project;
import com.evocode.mapper.ChatMessageMapper;
import com.evocode.mapper.ChatSessionMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplTest {

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private ProjectMapper projectMapper;
    private ChatStreamer streamer;
    private ChatServiceImpl svc;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        projectMapper = mock(ProjectMapper.class);
        streamer = mock(ChatStreamer.class);
        svc = new ChatServiceImpl(sessionMapper, messageMapper, projectMapper, streamer);
    }

    private Project project(Long id) {
        Project p = new Project();
        p.setId(id);
        return p;
    }

    private ChatSession session(Long id, Long projectId, String title) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setProjectId(projectId);
        s.setTitle(title);
        s.setCreatedAt(OffsetDateTime.now());
        return s;
    }

    @Test
    void createSession_success() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(sessionMapper.insert(any(ChatSession.class))).thenAnswer(inv -> {
            ChatSession s = inv.getArgument(0);
            s.setId(81L);
            return 1;
        });
        ChatCreateResp resp = svc.createSession(1L);
        assertEquals(81L, resp.id());
        assertEquals("新会话", resp.title());
        assertEquals(0, resp.messageCount());
    }

    @Test
    void createSession_projectMissing_throws2001() {
        when(projectMapper.selectById(9L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.createSession(9L));
        assertEquals(ErrorCode.PROJECT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void listSessions_sortedByLastMessageAtDesc() {
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        ChatSession a = session(1L, 1L, "A");
        ChatSession b = session(2L, 1L, "B");
        when(sessionMapper.selectList(any())).thenReturn(List.of(a, b));
        when(messageMapper.selectCount(any())).thenReturn(4L);
        ChatMessage m1 = new ChatMessage();
        m1.setCreatedAt(OffsetDateTime.now().minusSeconds(10)); // A 的最后消息更早
        ChatMessage m2 = new ChatMessage();
        m2.setCreatedAt(OffsetDateTime.now().minusSeconds(1)); // B 最新
        when(messageMapper.selectOne(any())).thenReturn(m1, m2); // 按遍历顺序 A、B
        ChatListResp resp = svc.listSessions(1L);
        assertEquals(2, resp.total());
        assertEquals(2L, resp.items().get(0).id()); // B 排前（lastMessageAt desc）
        assertEquals(4, resp.items().get(0).messageCount());
        assertEquals(1L, resp.items().get(1).id());
    }

    @Test
    void deleteSession_missing_throws2006() {
        when(sessionMapper.selectById(5L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.deleteSession(5L));
        assertEquals(ErrorCode.SESSION_NOT_FOUND.getCode(), ex.getCode());
        verify(messageMapper, never()).delete(any());
    }

    @Test
    void deleteSession_cascadeDeletesMessages() {
        when(sessionMapper.selectById(5L)).thenReturn(session(5L, 1L, "T"));
        svc.deleteSession(5L);
        verify(sessionMapper).deleteById(5L);
        verify(messageMapper).delete(any());
    }

    @Test
    void listMessages_sessionMissing_throws2006() {
        when(sessionMapper.selectById(5L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.listMessages(5L, 1, 20));
        assertEquals(ErrorCode.SESSION_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void listMessages_ok() {
        when(sessionMapper.selectById(5L)).thenReturn(session(5L, 1L, "T"));
        ChatMessage m = new ChatMessage();
        m.setId(1L);
        m.setRole("USER");
        m.setContent("hi");
        IPage<ChatMessage> page = mock(IPage.class);
        when(page.getRecords()).thenReturn(List.of(m));
        when(page.getTotal()).thenReturn(1L);
        when(messageMapper.selectPage(any(), any())).thenReturn(page);
        ChatMessageListResp resp = svc.listMessages(5L, 1, 20);
        assertEquals(1, resp.items().size());
        assertEquals("USER", resp.items().get(0).role());
    }

    @Test
    void sendMessage_duplicateWithin2s_throws2007() {
        when(sessionMapper.selectById(5L)).thenReturn(session(5L, 1L, "T"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        ChatMessage last = new ChatMessage();
        last.setRole("USER");
        last.setContent("相同问题");
        last.setCreatedAt(OffsetDateTime.now());
        when(messageMapper.selectOne(any())).thenReturn(last);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.sendMessage(5L, "相同问题", null, mock(SseEmitter.class)));
        assertEquals(ErrorCode.CHAT_TOO_FREQUENT.getCode(), ex.getCode());
        verify(messageMapper, never()).insert(any(ChatMessage.class));
    }

    @Test
    void sendMessage_success_insertsUserAndStreams() {
        when(sessionMapper.selectById(5L)).thenReturn(session(5L, 1L, "新会话"));
        when(projectMapper.selectById(1L)).thenReturn(project(1L));
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(10L);
            m.setCreatedAt(OffsetDateTime.now());
            return 1;
        });
        SseEmitter emitter = mock(SseEmitter.class);
        svc.sendMessage(5L, "这个项目为什么难维护？", null, emitter);
        verify(messageMapper).insert(any(ChatMessage.class));
        verify(sessionMapper).updateById(any(ChatSession.class));
        verify(streamer).stream(any(ChatSession.class), eq("这个项目为什么难维护？"),
                eq(null), eq(10L), eq(emitter));
    }
}
