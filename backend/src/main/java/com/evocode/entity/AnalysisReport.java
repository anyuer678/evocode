package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evocode.config.PgJsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/** analysis_report 表（SPI-6 报告拆表：07 §3.2 演进，报告独立存储）。 */
@Data
@TableName(value = "analysis_report", autoResultMap = true)
public class AnalysisReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long analysisId;

    /** 健康分（列表排序列，替代 report_json->>'healthScore' 子查询；非数字为 NULL） */
    private Integer healthScore;

    /** EXCELLENT/GOOD/FAIR/POOR */
    private String level;

    /** 报告概述 */
    private String summary;

    /** 完整报告（AD-13 结构，详情/导出用） */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> reportJson;

    private OffsetDateTime createdAt;
}
