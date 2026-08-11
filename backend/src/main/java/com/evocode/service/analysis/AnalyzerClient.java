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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
            .version(HttpClient.Version.HTTP_1_1) // 端到端实测：默认 HTTP/2 与 uvicorn(h1) 协商丢 POST body → 422
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final RestClient client;
    private final RestClient docClient;
    private final String analyzerUrl;
    private final ObjectMapper objectMapper;

    public AnalyzerClient(EvocodeProperties props, RestClient.Builder builder,
                          ObjectMapper objectMapper) {
        this.analyzerUrl = props.getAnalyzerUrl();
        this.client = builder
                .baseUrl(analyzerUrl)
                .build();
        this.objectMapper = objectMapper;
        // 审查 M3：doc 生成是长耗时 LLM 调用，用独立带超时的 client（不覆盖共享 builder，
        // 避免破坏 MockRestServiceServer 等注入的 requestFactory）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(10_000, (int) props.getLlmTimeoutSeconds() * 1000);
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.docClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(analyzerUrl)
                .build();
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
                String errBody = "";
                try (InputStream is = resp.body()) {
                    byte[] raw = is.readAllBytes();
                    errBody = new String(raw, StandardCharsets.UTF_8);
                    if (errBody.length() > 300) {
                        errBody = errBody.substring(0, 300) + "...(截断)"; // 防刷屏（审查 nit）
                    }
                } catch (Exception ignored) {
                    // body 读取失败不影响主错误
                }
                log.error("chat 非 200 status={} body={}", resp.statusCode(), errBody);
                throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                        "chat 服务错误：" + resp.statusCode() + " " + errBody);
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

    /** /analyze/v1/doc 请求（06 §5.9，P7b）。 */
    public record DocRequest(Long projectId, String docType, Map<String, Object> scan,
                             Map<String, Object> arch, Map<String, Object> projectInfo,
                             String codeDir) {
    }

    /** /analyze/v1/doc 响应。 */
    public record DocResp(String docType, String title, String content) {
    }

    /**
     * 调 /analyze/v1/doc（06 §5.9）：文档生成。不可达/错误 → 3001
     * （analyzer 内部 LLM_NO_KEY 400 / LLM_FAILED 502 统一映射为 3001，前端提示）。
     */
    public DocResp doc(Long projectId, String docType, Map<String, Object> scan,
                       Map<String, Object> arch, Map<String, Object> projectInfo,
                       String codeDir) {
        try {
            return docClient.post()
                    .uri("/analyze/v1/doc")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DocRequest(projectId, docType, scan, arch, projectInfo, codeDir))
                    .retrieve()
                    .body(DocResp.class);
        } catch (RestClientResponseException e) {
            // 审查 M8：解析 analyzer 错误体 {"error":{"code":"LLM_NO_KEY","message":"…"}}
            // 区分'未配置 key'与'服务故障'，避免统一抹平成 3001
            String code = parseAnalyzerErrorCode(e.getResponseBodyAsString());
            if ("LLM_NO_KEY".equals(code)) {
                throw new BusinessException(ErrorCode.LLM_NO_KEY,
                        "LLM 未配置，无法生成文档（文档无法规则降级）");
            }
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "文档服务不可达或内部错误：" + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ANALYZER_UNREACHABLE,
                    "文档服务不可达或内部错误：" + e.getMessage());
        }
    }

    /** 从 analyzer 错误体提取 code；兼容 FastAPI 的 {"detail": {"error": {...}}} 包装。 */
    String parseAnalyzerErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode err = node.path("error");
            if (err.isMissingNode() || err.isNull()) {
                err = node.path("detail").path("error");
            }
            return err.path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
