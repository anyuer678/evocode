package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evocode.config.PgJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/** analysis 表（07 §3.2）。 */
@Data
@TableName(value = "analysis", autoResultMap = true)
public class Analysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** FULL/QUALITY/ARCH/EVOLUTION */
    private String type;

    /** PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED */
    private String status;

    private Integer progress;

    /** QUEUED/SCAN/SCAN_DONE/REPORT/DONE */
    private String stage;

    private String errorCode;

    private String errorMessage;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> reportJson;

    /** LLM/RULES */
    private String reportSource;

    private String promptVersion;

    private String analyzerVersion;

    private OffsetDateTime regeneratedAt;

    private OffsetDateTime startedAt;

    private OffsetDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
