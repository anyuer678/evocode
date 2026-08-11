package com.evocode.dto.architecture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** analyzer /analyze/v1/architecture 响应（06 §5.5，nodeKey 语义）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArchResultResp(List<Node> nodes, List<Edge> edges, List<Violation> violations) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String nodeKey, String name, String nodeType,
                       String filePath, Map<String, Object> metrics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(String sourceNodeKey, String targetNodeKey, String relation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Violation(String violationType, String description,
                            String sourceNodeKey, String targetNodeKey,
                            String severity, String suggestion) {
    }
}
