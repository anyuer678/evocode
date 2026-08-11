package com.evocode.dto.project;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 项目列表项（06 §3.2，含 healthScore JOIN 子查询）。 */
@Data
public class ProjectSummaryResp {

    private Long id;
    private String name;
    private String sourceType;
    private Map<String, Object> langStats;
    private List<String> frameworkTags;
    private Long locTotal;
    private Integer fileCount;
    private String status;
    private Integer healthScore;
    private OffsetDateTime lastAnalyzedAt;
    private OffsetDateTime createdAt;
}
