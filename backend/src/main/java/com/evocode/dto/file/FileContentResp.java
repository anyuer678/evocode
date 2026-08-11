package com.evocode.dto.file;

/** 文件内容（06 §3.8 content）。 */
public record FileContentResp(String path, String language, Integer loc, String content, Boolean truncated) {
}
