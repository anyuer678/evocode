package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** file_node 表（07 §3.3，快照无逻辑删除）。 */
@Data
@TableName("file_node")
public class FileNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    private String path;

    /** OTHER=未识别 */
    private String language;

    private Integer loc;

    private Integer sizeBytes;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
