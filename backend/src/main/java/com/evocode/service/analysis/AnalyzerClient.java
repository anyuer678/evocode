package com.evocode.service.analysis;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.config.EvocodeProperties;
import com.evocode.dto.architecture.ArchResultResp;
import com.evocode.dto.evolution.EvolutionResp;
import com.evocode.dto.scan.ScanResultResp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * analyzer 内部服务客户端（04 §3.2：错误映射 3xxx）。
 * LLM 出口唯一在 analyzer，backend 永不直连。
 */
@Slf4j
@Service
public class AnalyzerClient {

    private static final HttpClient STREAM_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final RestClient client;
    private final String analyzerUrl;
    private final ObjectMapper objectMapper;

    public AnalyzerClient(EvocodeProperties props, RestClient.Builder builder,
                          ObjectMapper objectMapper) {
        this.analyzerUrl = props.getAnalyzerUrl();
        this.client = builder
                .baseUrl(analyzerUrl)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 调 /analyze/v1/scan（06 §5.1）。analyzer 不可达/5xx → 3001。
     */
    public ScanResultResp scan(Long projectId, String codeDir) {
        try {
            return client.post()
                    .uri("/analyze/v1/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ScanRequest(projectId, codeDir))
                    .retrieve()
                    .body(ScanResultResp.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "扫描服务不可达或内部错误：" + e.getMessage());
        }
    }

    /**
     * 调 /analyze/v1/report（06 §5.2）。LLM 失败在 analyzer 内部降级 RULES，
     * 故此处任何响应均视为成功（HTTP 200）。
     *
     * @param quality Sonar 质量指标（metrics），无质量数据时传 null
     */
    public ReportResp report(Long projectId, ScanResultResp scan, Map<String, Object> quality,
                             List<Map<String, Object>> historyReports) {
        try {
            return client.post()
                    .uri("/analyze/v1/report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReportRequest(projectId, scan, quality, null, null, historyReports, false))
                    .retrieve()
                    .body(ReportResp.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "报告服务不可达或内部错误：" + e.getMessage());
        }
    }

    /**
     * 调 /analyze/v1/quality（06 §5.3）。Sonar 不可用 analyzer 内部返回
     * metrics.available=false（HTTP 200），此处视为成功。
     */
    public QualityResp quality(Long projectId, String codeDir) {
        try {
            return client.post()
                    .uri("/analyze/v1/quality")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new QualityRequest(projectId, codeDir))
                    .retrieve()
                    .body(QualityResp.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "质量服务不可达或内部错误：" + e.getMessage());
        }
    }

    /**
     * 调 /analyze/v1/architecture（06 §5.5）。当前 URL 不可达时按可承受降级处理，
     * 双端降级为建议项。
     */
    public ArchResultResp architecture(Long projectId, String codeDir) {
        try {
            return client.post()
                    .uri("/analyze/v1/architecture")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ArchitectureRequest(projectId, codeDir))
                    .retrieve()
                    .body(ArchResultResp.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "架构分析服务不可达或内部错误：" + e.getMessage());
        }
    }

    /**
     * 调 /analyze/v1/evolution（06 §5.6）。非 git 仓库 analyzer 返回 available=false；
     * 不可达/5xx → 3001（由 AnalysisRunner.runEvolution 捕获降级，不阻塞报告）。
     */
    public EvolutionResp evolution(Long projectId, String gitDir, int rangeDays) {
        try {
            return client.post()
                    .uri("/analyze/v1/evolution")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EvolutionRequest(projectId, gitDir, rangeDays))
                    .retrieve()
                    .body(EvolutionResp.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "演化统计服务不可达或内部错误：" + e.getMessage());
        }
    }

    /**
     * 调 /analyze/v1/chat（06 §5.7）：SSE 生成端。
     * 返回 analyzer 响应体输入流（text/event-stream），由调用方逐行读取；
     * 非 200 / 不可达 → 3001。
     */
    public BufferedReader chatStream(Long projectId, Map<String, Object> systemContext,
                                     List<Map<String, String>> history, String query,
                                     ChatFileRef fileRef) {
        try {
            String body = objectMapper.writeValueAsString(
                    new AnalyzerChatRequest(projectId, systemContext, history, query, fileRef));
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(analyzerUrl + "/analyze/v1/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<InputStream> resp = STREAM_HTTP.send(
                    req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                        "chat 服务错误：" + resp.statusCode());
            }
            return new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "chat 服务不可达：" + e.getMessage());
        }
    }

    public record ScanRequest(Long projectId, String codeDir) {
    }

    public record ArchitectureRequest(Long projectId, String codeDir) {
    }

    public record EvolutionRequest(Long projectId, String gitDir, Integer rangeDays) {
    }

    public record QualityRequest(Long projectId, String codeDir) {
    }

    /** /analyze/v1/quality 响应：{metrics, issues}。metrics.available=false 表示 Sonar 未启用。 */
    public record QualityResp(Map<String, Object> metrics, List<Map<String, Object>> issues) {
    }

    public record ReportRequest(Long projectId, ScanResultResp scan, Map<String, Object> quality,
                                Object arch, Object evolution, List<Map<String, Object>> historyReports,
                                boolean regenerate) {
    }

    /** /analyze/v1/report 响应：{source, promptVersion, report}。 */
    public record ReportResp(String source, String promptVersion, Map<String, Object> report) {
    }

    /** /analyze/v1/chat 请求 fileRef（06 §5.7）：用户 @ 的文件全文。 */
    public record ChatFileRef(String path, String content) {
    }

    /** /analyze/v1/chat 请求（06 §5.7）。 */
    public record AnalyzerChatRequest(Long projectId, Map<String, Object> systemContext,
                                      List<Map<String, String>> history, String query,
                                      ChatFileRef fileRef) {
    }
}
