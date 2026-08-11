package com.evocode.dto.project;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 创建项目响应（06 §3.1）。 */
@Data
@Builder
public class ProjectResp {

    private Long id;
    private String name;
    private String sourceType;
    private String status;
    private String storagePath;
    private Map<String, Object> langStats;
    private Long locTotal;
    private Integer fileCount;
    private List<String> frameworkTags;
    private OffsetDateTime lastAnalyzedAt;
    private OffsetDateTime createdAt;
}
