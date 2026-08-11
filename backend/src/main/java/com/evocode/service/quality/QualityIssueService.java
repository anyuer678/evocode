package com.evocode.service.quality;

import com.evocode.dto.quality.QualityIssuesResp;

/**
 * 质量 issues 查询（docs/06-API契约.md §3.10）。
 */
public interface QualityIssueService {

    /** 分页查询项目质量 issues，并聚合指标。 */
    QualityIssuesResp query(Long projectId, String severity, String kind, String status,
                            int page, int size);
}
