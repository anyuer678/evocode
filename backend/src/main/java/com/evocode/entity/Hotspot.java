package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evocode.config.PgJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** hotspot（07 §3.16，P5）：演化热点。evidence 见 07 §5.6。 */
@Data
@TableName(value = "hotspot", autoResultMap = true)
public class Hotspot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    private String module;

    /** HIGH / MEDIUM */
    private String riskLevel;

    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> evidence;

    /** v1.0 AI 医生填充，当前为 null */
    private String aiConclusion;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
