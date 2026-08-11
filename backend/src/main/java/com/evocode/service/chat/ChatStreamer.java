package com.evocode.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evocode.entity.Analysis;
import com.evocode.entity.ChatMessage;
import com.evocode.entity.ChatSession;
import com.evocode.entity.Project;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ChatMessageMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.analysis.AnalyzerClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 医生 SSE 透传（06 §4.5）：backend → analyzer /analyze/v1/chat（透传不缓冲），
 * 事件流 delta/citations/done/error 原样转发；done 时以落库后的 messageId 补充。
 * 独立 bean + @Async（chatExecutor）使异步跨 bean 生效。
 */
@Slf4j
@Component
public class ChatStreamer {

    private static final int HISTORY_ROUNDS = 6;
    private static final int FILE_REF_MAX_CHARS = 4000;

    private final AnalyzerClient analyzerClient;
    private final ChatMessageMapper chatMessageMapper;
    private final ProjectMapper projectMapper;
    private final AnalysisMapper analysisMapper;
    private final ObjectMapper objectMapper;

    public ChatStreamer(AnalyzerClient analyzerClient, ChatMessageMapper chatMessageMapper,
                        ProjectMapper projectMapper, AnalysisMapper analysisMapper,
                        ObjectMapper objectMapper) {
        this.analyzerClient = analyzerClient;
        this.chatMessageMapper = chatMessageMapper;
        this.projectMapper = projectMapper;
        this.analysisMapper = analysisMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式透传：读 analyzer SSE → 转发前端 → done 落库 assistant 消息。
     */
    @Async("chatExecutor")
    public void stream(ChatSession session, String query, String fileRef,
                       Long currentUserMsgId, SseEmitter emitter) {
        try {
            Map<String, Object> systemContext = buildSystemContext(session.getProjectId());
            List<Map<String, String>> history = loadHistory(session.getId(), currentUserMsgId);
            AnalyzerClient.ChatFileRef ref = loadFileRef(session.getProjectId(), fileRef);
            RelayResult result = relay(session.getId(), emitter,
                    analyzerClient.chatStream(session.getProjectId(), systemContext,
                            history, query, ref));
            if (result.handled()) {
                return; // error 分支已落库并 complete
            }
            if (result.answer().isEmpty()) {
                insertAssistant(session.getId(), "回答生成失败：流式响应为空", null);
                sendEvent(emitter, "error",
                        Map.of("code", "LLM_FAILED", "message", "回答生成失败：流式响应为空"));
                emitter.complete();
                return;
            }
            ChatMessage saved = insertAssistant(session.getId(), result.answer(),
                    result.citations());
            sendEvent(emitter, "done", Map.of("messageId", saved.getId()));
            emitter.complete();
        } catch (Exception e) {
            log.error("chat stream failed session={} err={}", session.getId(), e.getMessage());
            try {
                insertAssistant(session.getId(),
                        "回答生成失败（连接中断）：" + e.getMessage(), null);
                sendEvent(emitter, "error",
                        Map.of("code", "LLM_FAILED", "message", e.getMessage()));
            } catch (Exception ignored) {
                // 前端已断开，忽略
            }
            emitter.complete();
        }
    }

    /** 逐行读 analyzer SSE 流并转发；done 前收集 citations 一并返回。 */
    private RelayResult relay(Long sessionId, SseEmitter emitter, BufferedReader reader)
            throws Exception {
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> citations = new ArrayList<>();
        try (reader) {
            String event = null;
            String line;
            while ((line = reader.readLine()) != null) {
                String l = line.trim();
                if (l.isEmpty()) {
                    continue;
                }
                if (l.startsWith("event: ")) {
                    event = l.substring(7);
                } else if (l.startsWith("data: ")) {
                    JsonNode node = objectMapper.readTree(l.substring(6));
                    if ("delta".equals(event)) {
                        String delta = node.path("content").asText("");
                        if (delta.isEmpty()) {
                            continue;
                        }
                        answer.append(delta);
                        sendEvent(emitter, "delta", Map.of("content", delta));
                    } else if ("citations".equals(event)) {
                        // analyzer 生成的引用（已做集合校验）；透传 + 随 done 落库。
                        citations = objectMapper.convertValue(
                                node.path("items"),
                                new TypeReference<List<Map<String, Object>>>() {
                                });
                        if (citations == null) {
                            citations = new ArrayList<>();
                        }
                        sendEvent(emitter, "citations", Map.of("items", citations));
                    } else if ("error".equals(event)) {
                        String code = node.path("code").asText("LLM_FAILED");
                        String message = node.path("message").asText("回答生成失败");
                        insertAssistant(sessionId, "回答生成失败：" + message, null);
                        sendEvent(emitter, "error", Map.of("code", code, "message", message));
                        emitter.complete();
                        return new RelayResult(answer.toString(), citations, true);
                    }
                    // done：analyzer 无 messageId，不转发（后端落库后自发送）
                }
            }
        }
        return new RelayResult(answer.toString(), citations, false);
    }

    private record RelayResult(String answer, List<Map<String, Object>> citations,
                               boolean handled) {
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private ChatMessage insertAssistant(Long sessionId, String content,
                                        List<Map<String, Object>> citations) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole("ASSISTANT");
        msg.setContent(content);
        msg.setCitations(citations);
        chatMessageMapper.insert(msg);
        return msg;
    }

    private Map<String, Object> buildSystemContext(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        Map<String, Object> ctx = new HashMap<>();
        if (p == null) {
            ctx.put("projectName", "未知项目");
            return ctx;
        }
        ctx.put("projectName", p.getName());
        ctx.put("projectSummary", p.getDescription() == null ? "" : p.getDescription());
        Map<String, Object> langStats = p.getLangStats();
        if (langStats != null && !langStats.isEmpty()) {
            String top = langStats.entrySet().stream()
                    .max(Map.Entry.comparingByValue(
                            Comparator.comparingDouble(v -> ((Number) v).doubleValue())))
                    .map(Map.Entry::getKey).orElse("");
            ctx.put("language", top);
        } else {
            ctx.put("language", "");
        }
        List<String> tags = p.getFrameworkTags();
        ctx.put("framework", tags == null || tags.isEmpty() ? "" : tags.get(0));
        ctx.put("loc", p.getLocTotal() == null ? 0 : p.getLocTotal());
        Analysis latest = analysisMapper.selectOne(
                new LambdaQueryWrapper<Analysis>()
                        .eq(Analysis::getProjectId, projectId)
                        .orderByDesc(Analysis::getId)
                        .last("LIMIT 1"));
        String summary = "";
        if (latest != null && latest.getReportJson() != null
                && latest.getReportJson().get("summary") != null) {
            summary = String.valueOf(latest.getReportJson().get("summary"));
        }
        ctx.put("latestReportSummary", summary);
        return ctx;
    }

    /** 最近 HISTORY_ROUNDS 轮（12 条）历史，剔除当前 user 消息，按时间正序。 */
    private List<Map<String, String>> loadHistory(Long sessionId, Long currentUserMsgId) {
        List<ChatMessage> messages = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .ne(ChatMessage::getId, currentUserMsgId)
                        .orderByDesc(ChatMessage::getId)
                        .last("LIMIT " + (HISTORY_ROUNDS * 2)));
        Collections.reverse(messages);
        List<Map<String, String>> history = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            history.add(Map.of(
                    "role", "USER".equals(m.getRole()) ? "user" : "assistant",
                    "content", m.getContent() == null ? "" : m.getContent()));
        }
        return history;
    }

    /** fileRef（storagePath 相对路径）→ 全文（防目录穿越；缺失/超限 → null）。 */
    private AnalyzerClient.ChatFileRef loadFileRef(Long projectId, String fileRef) {
        if (fileRef == null || fileRef.isBlank()) {
            return null;
        }
        Project p = projectMapper.selectById(projectId);
        if (p == null || p.getStoragePath() == null) {
            return null;
        }
        try {
            Path base = Path.of(p.getStoragePath()).toAbsolutePath().normalize();
            Path target = base.resolve(fileRef).normalize();
            if (!target.startsWith(base)) {
                return null; // 目录穿越
            }
            if (!Files.isRegularFile(target)) {
                return null;
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (content.length() > FILE_REF_MAX_CHARS) {
                content = content.substring(0, FILE_REF_MAX_CHARS);
            }
            return new AnalyzerClient.ChatFileRef(fileRef, content);
        } catch (Exception e) {
            log.warn("fileRef 读取失败 {}: {}", fileRef, e.getMessage());
            return null;
        }
    }
}
