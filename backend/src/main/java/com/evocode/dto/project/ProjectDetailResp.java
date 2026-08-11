package com.evocode.dto.project;

import com.evocode.dto.analysis.LatestAnalysisResp;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 项目详情（06 §3.3）。 */
@Data
@Builder
public class ProjectDetailResp {

    private Long id;
    private String name;
    private String description;
    private String sourceType;
    private String repoUrl;
    private String status;
    private Map<String, Object> langStats;
    private List<String> frameworkTags;
    private Long locTotal;
    private Integer fileCount;
    private Integer ignoredCount;
    private OffsetDateTime lastAnalyzedAt;
    private LatestAnalysisResp latestAnalysis;
    private OffsetDateTime createdAt;
}
