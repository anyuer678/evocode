package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** architecture_edge（07 §3.7）。 */
@Data
@TableName("architecture_edge")
public class ArchitectureEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    private Long sourceNodeId;

    private Long targetNodeId;

    /** CALL（当前唯一） */
    private String relation;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
