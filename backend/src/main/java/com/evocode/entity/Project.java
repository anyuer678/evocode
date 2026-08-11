package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evocode.config.PgJsonbTypeHandler;
import com.evocode.config.PgStringArrayTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** project 表（07 §3.1）。 */
@Data
@TableName(value = "project", autoResultMap = true)
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** ZIP / GIT */
    private String sourceType;

    private String repoUrl;

    /** data/projects/{id} */
    private String storagePath;

    /** CREATED/ANALYZING/READY/FAILED */
    private String status;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> langStats;

    @TableField(typeHandler = PgStringArrayTypeHandler.class)
    private List<String> frameworkTags;

    private Long locTotal;

    private Integer fileCount;

    private Integer ignoredCount;

    private OffsetDateTime lastAnalyzedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
