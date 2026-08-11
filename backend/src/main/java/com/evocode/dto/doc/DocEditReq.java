package com.evocode.dto.doc;

/**
 * 人工编辑请求（06 §3.14）：编辑后 edited=true、version+1。
 */
public record DocEditReq(String content) {
}
