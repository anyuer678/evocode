package com.evocode.service.chat;

import com.evocode.entity.ChatMessage;
import com.evocode.entity.ChatSession;
import com.evocode.entity.Project;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ChatMessageMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.analysis.AnalyzerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatStreamer SSE 透传测试：mock analyzer 流 + SseEmitter，直接调用 stream()
 * （不走 @Async 代理），断言事件转发顺序与 assistant 落库。
 */
class ChatStreamerTest {

    private AnalyzerClient analyzerClient;
    private ChatMessageMapper messageMapper;
    private ProjectMapper projectMapper;
    private AnalysisMapper analysisMapper;
    private ChatStreamer streamer;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        analyzerClient = mock(AnalyzerClient.class);
        messageMapper = mock(ChatMessageMapper.class);
        projectMapper = mock(ProjectMapper.class);
        analysisMapper = mock(AnalysisMapper.class);
        streamer = new ChatStreamer(analyzerClient, messageMapper, projectMapper,
                analysisMapper, new ObjectMapper());
        emitter = mock(SseEmitter.class);
    }

    private ChatSession session() {
        ChatSession s = new ChatSession();
        s.setId(5L);
        s.setProjectId(1L);
        return s;
    }

    private Project project() {
        Project p = new Project();
        p.setId(1L);
        p.setName("demo");
        p.setStoragePath("data/projects/1");
        return p;
    }

    private String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    @Test
    void stream_normalFlow_forwardsDeltaCitationsDone() throws Exception {
        when(analyzerClient.chatStream(anyLong(), any(), anyList(), anyString(), any()))
                .thenReturn(new BufferedReader(new StringReader(
                        sse("delta", "{\"content\":\"结论：\"}")
                                + sse("delta", "{\"content\":\"见 [a.java:1]。\"}")
                                + sse("citations",
                                "{\"items\":[{\"file\":\"a.java\",\"line\":1,\"excerpt\":\"x\"}]}")
                                + sse("done", "{}"))));
        when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(99L);
            return 1;
        });
        streamer.stream(session(), "问题？", null, 1L, emitter);
        // 2 delta + 1 citations + 1 done
        verify(emitter, org.mockito.Mockito.times(4)).send(any(SseEmitter.SseEventBuilder.class));
        verify(messageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void stream_errorEvent_insertsFailureMessage() throws Exception {
        when(analyzerClient.chatStream(anyLong(), any(), anyList(), anyString(), any()))
                .thenReturn(new BufferedReader(new StringReader(
                        sse("error", "{\"code\":\"LLM_NO_KEY\",\"message\":\"未配置\"}"))));
        streamer.stream(session(), "问题？", null, 1L, emitter);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class)); // 仅 error 1 次
        verify(messageMapper).insert(any(ChatMessage.class)); // 失败消息落库
    }

    @Test
    void stream_exception_insertsFailureMessage() throws Exception {
        when(analyzerClient.chatStream(anyLong(), any(), anyList(), anyString(), any()))
                .thenThrow(new RuntimeException("analyzer down"));
        streamer.stream(session(), "问题？", null, 1L, emitter);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(messageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void stream_emptyAnswer_insertsFailure() throws Exception {
        when(analyzerClient.chatStream(anyLong(), any(), anyList(), anyString(), any()))
                .thenReturn(new BufferedReader(new StringReader(
                        sse("delta", "{\"content\":\"\"}") + sse("done", "{}"))));
        streamer.stream(session(), "问题？", null, 1L, emitter);
        verify(messageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void stream_usesProjectContext() throws Exception {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(analyzerClient.chatStream(anyLong(), any(), anyList(), anyString(), any()))
                .thenReturn(new BufferedReader(new StringReader(
                        sse("delta", "{\"content\":\"ok\"}") + sse("done", "{}"))));
        streamer.stream(session(), "问题？", null, 1L, emitter);
        verify(analyzerClient).chatStream(anyLong(), any(), anyList(), anyString(), any());
    }
}
