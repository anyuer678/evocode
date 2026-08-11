package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 生成文档（docs/07-数据字典.md §3.12，V005）。
 * 每项目每 docType 一条；编辑后 edited=true、version 递增。
 */
@Data
@TableName("generated_doc")
public class GeneratedDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** README / ARCH / API */
    private String docType;

    private String title;

    private String content;

    private Integer version;

    /** 人工编辑过 → 重新生成需确认 */
    private Boolean edited;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
