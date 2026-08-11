package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** file_change_stat（07 §3.10，P5）：文件级变更聚合/热点。 */
@Data
@TableName("file_change_stat")
public class FileChangeStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    private String filePath;

    private Integer commitCount;

    private Integer linesAdded;

    private Integer linesRemoved;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
