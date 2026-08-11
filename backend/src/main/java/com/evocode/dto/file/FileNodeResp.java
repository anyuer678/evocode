package com.evocode.dto.file;

/** 文件列表项（06 §3.8 files）。 */
public record FileNodeResp(String path, String language, Integer loc, Integer sizeBytes) {
}
