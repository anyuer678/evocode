package com.evocode.dto.debt;

import java.time.OffsetDateTime;

/**
 * 技术债项（06 §3.12）。
 */
public record TechDebtResp(
        Long id,
        String source,
        String title,
        String level,
        String description,
        String suggestion,
        String status,
        Long refAnalysisId,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt
) {
}
