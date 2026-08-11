package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.OffsetDateTime;

/**
 * 技术债（docs/07-数据字典.md §3.11，V005）。
 * 来源：ARCH/QUALITY/DEPEND/EVOLUTION（分析聚合）/ AI_DOCTOR/MANUAL（预留）。
 */
@Data
@Accessors(chain = true)
@TableName("tech_debt")
public class TechDebt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** ARCH / QUALITY / DEPEND / EVOLUTION / AI_DOCTOR / MANUAL */
    private String source;

    private String title;

    /** HIGH / MEDIUM / LOW */
    private String level;

    private String description;

    private String suggestion;

    /** OPEN / DOING / DONE / WONTFIX */
    private String status;

    private Long refAnalysisId;

    private String resolveNote;

    private String wonfixReason;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    private OffsetDateTime resolvedAt;
}
