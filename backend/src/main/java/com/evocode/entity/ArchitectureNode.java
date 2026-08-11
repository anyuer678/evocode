package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evocode.config.PgJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/** architecture_node（07 §3.6）。 */
@Data
@TableName(value = "architecture_node", autoResultMap = true)
public class ArchitectureNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    private String nodeKey;

    private String name;

    /** CONTROLLER / SERVICE / REPOSITORY / ENTITY / UTIL / MODULE / OTHER */
    private String nodeType;

    private String filePath;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
