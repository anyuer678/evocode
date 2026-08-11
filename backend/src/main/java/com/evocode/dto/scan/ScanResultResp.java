package com.evocode.dto.scan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** /analyze/v1/scan 响应（06 §5.1）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanResultResp(
        Map<String, Object> languages,
        Long locTotal,
        Integer fileCount,
        Integer ignoredCount,
        List<String> frameworks,
        Boolean hasBackend,
        Boolean hasFrontend,
        List<String> dbHint,
        List<ScanFileResp> files,
        Integer skippedBigFiles,
        Boolean truncated) {
}
