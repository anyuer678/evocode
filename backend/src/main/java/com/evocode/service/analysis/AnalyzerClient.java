package com.evocode.service.analysis;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.config.EvocodeProperties;
import com.evocode.dto.architecture.ArchResultResp;
import com.evocode.dto.scan.ScanResultResp;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * analyzer 内部服务客户端（04 §3.2：错误映射 3xxx）。
 * LLM 出口唯一在 analyzer，backend 永不直连。
 */
@Slf4j
@Service
public class AnalyzerClient {

    private final RestClient client;

    public AnalyzerClient(EvocodeProperties props, RestClient.Builder builder) {
        this.client = builder
                .baseUrl(props.getAnalyzerUrl())
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

    public record ScanRequest(Long projectId, String codeDir) {
    }

    public record ArchitectureRequest(Long projectId, String codeDir) {
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
}
