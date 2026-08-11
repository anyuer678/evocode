package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** arch_violation（07 §3.8）。 */
@Data
@TableName("arch_violation")
public class ArchViolation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    /** LAYER_VIOLATION / ... */
    private String violationType;

    private String description;

    private Long sourceNodeId;

    private Long targetNodeId;

    /** HIGH / MEDIUM / LOW */
    private String severity;

    private String suggestion;

    /** v1.0 AI 医生补充，当前为 NULL */
    private String aiNote;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
