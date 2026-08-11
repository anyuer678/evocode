package com.evocode.dto.architecture;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** GET /projects/{id}/architecture 响应（06 §3.11，落库后 id 语义）。 */
public record ArchitectureResp(List<NodeResp> nodes, List<EdgeResp> edges, List<ViolationResp> violations) {

    public record NodeResp(Long id, String nodeKey, String name, String nodeType,
                           String filePath, Map<String, Object> metrics) {
    }

    public record EdgeResp(Long id, Long sourceNodeId, Long targetNodeId, String relation) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ViolationResp(Long id, String violationType, String description,
                                Long sourceNodeId, Long targetNodeId,
                                String severity, String suggestion, String aiNote) {
    }
}
