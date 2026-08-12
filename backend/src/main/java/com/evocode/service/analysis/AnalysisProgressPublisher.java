package com.evocode.service.analysis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 分析进度 SSE 广播（P9e E1）：按 projectId 注册 SseEmitter，
 * AnalysisRunner 状态机变更点调用 {@link #publish} 推送。
 *
 * <p>断线不重放历史（前端用现有轮询接口兜底）；超时/断开/异常清理注册。
 * 单用户本地部署（无鉴权），按 projectId 隔离；同一项目多标签页各自订阅
 * （CopyOnWriteArraySet，互不覆盖）。
 */
@Slf4j
@Component
public class AnalysisProgressPublisher {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    /** projectId → 订阅的 emitter 集合（多标签页各自订阅，互不覆盖）。 */
    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** 订阅项目进度：返回 emitter 供 controller 返回；超时/断开自动清理。 */
    public SseEmitter subscribe(Long projectId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        subscribers.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(emitter);
        emitter.onTimeout(() -> remove(projectId, emitter, true));
        emitter.onCompletion(() -> remove(projectId, emitter, false));
        emitter.onError(e -> remove(projectId, emitter, true));
        log.info("订阅分析进度 projectId={}（当前订阅数={}）", projectId, subscriberCount(projectId));
        return emitter;
    }

    /** 状态机变更点推送；无订阅者时 no-op。 */
    public void publish(Long projectId, Long analysisId, String status, String stage,
                        int progress, String message) {
        Set<SseEmitter> set = subscribers.get(projectId);
        if (set == null || set.isEmpty()) {
            return;
        }
        ProgressEvent event = new ProgressEvent(analysisId, status, stage, progress, message);
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event()
                        .name("analysis-progress")
                        .data(event));
                log.debug("推送分析进度 projectId={} analysisId={} {}({})", projectId, analysisId, status, progress);
            } catch (IOException | IllegalStateException e) {
                log.debug("进度推送失败（连接可能已断开），清理 projectId={}：{}", projectId, e.getMessage());
                remove(projectId, emitter, true);
            }
        }
    }

    /** 清理订阅；断开场景显式 complete 立即释放连接（避免依赖 180s 超时）。 */
    private void remove(Long projectId, SseEmitter emitter, boolean complete) {
        Set<SseEmitter> set = subscribers.get(projectId);
        if (set != null) {
            set.remove(emitter);
            if (set.isEmpty()) {
                subscribers.remove(projectId, set);
            }
        }
        if (complete) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已完成的 emitter complete 幂等
            }
        }
    }

    private int subscriberCount(Long projectId) {
        Set<SseEmitter> set = subscribers.get(projectId);
        return set == null ? 0 : set.size();
    }

    /** SSE 事件载荷（契约 §E1）。 */
    public record ProgressEvent(Long analysisId, String status, String stage,
                                int progress, String message) {
    }
}
