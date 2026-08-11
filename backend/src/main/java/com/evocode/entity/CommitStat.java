package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** commit_stat（07 §3.9，P5）。 */
@Data
@TableName("commit_stat")
public class CommitStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    private String commitHash;

    private String authorName;

    private String authorEmail;

    private OffsetDateTime committedAt;

    private Integer linesAdded;

    private Integer linesRemoved;

    private Integer filesChanged;

    private String message;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
