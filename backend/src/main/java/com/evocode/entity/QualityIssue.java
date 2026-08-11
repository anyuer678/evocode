package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 质量 issue（docs/07-数据字典.md §3.5）。
 * ai_* 字段由 P3d 异步解释回填；status 由用户/系统维护。
 */
@Data
@TableName("quality_issue")
public class QualityIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    /** SONAR */
    private String source;

    /** BLOCKER/CRITICAL/MAJOR/MINOR/INFO */
    private String severity;

    /** BUG/VULNERABILITY/SMELL */
    private String kind;

    private String ruleKey;

    private String filePath;

    private Integer line;

    private String message;

    private String aiExplanation;

    private String aiSuggestion;

    /** PENDING/DONE/FAILED */
    private String aiStatus;

    /** OPEN/IGNORED/FIXED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
