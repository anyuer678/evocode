package com.evocode.dto.scan;

/** /analyze/v1/scan 返回的单文件（06 §5.1）。 */
public record ScanFileResp(String path, String language, Integer loc, Integer sizeBytes) {
}
