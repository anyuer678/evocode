package com.evocode.dto.doc;

import java.time.OffsetDateTime;

/**
 * 文档项（06 §3.14）。
 */
public record DocResp(
        Long id,
        String docType,
        String title,
        String content,
        Integer version,
        Boolean edited,
        OffsetDateTime createdAt
) {
}
