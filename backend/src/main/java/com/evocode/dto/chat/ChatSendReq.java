package com.evocode.dto.chat;

/**
 * 发消息请求（06 §4.1）。fileRef 为用户 @ 的文件相对路径（storagePath 下），可选。
 */
public record ChatSendReq(String content, String fileRef) {
}
