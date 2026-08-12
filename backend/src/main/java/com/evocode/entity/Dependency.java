package com.evocode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** dependency（07 §3.4，P9d）：依赖清单（EOL 判定结果）。 */
@Data
@TableName("dependency")
public class Dependency {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long analysisId;

    /** maven / npm / pip / go */
    private String ecosystem;

    /** maven 用 groupId:artifactId */
    private String name;

    private String version;

    /** 规则表建议的最新版本 */
    private String latestVersion;

    /** LOW / MEDIUM / HIGH；null=未命中规则（未知版本） */
    private String riskLevel;

    /** EOL 等风险原因 */
    private String riskReason;

    /** 升级建议（本期 = 规则 reason） */
    private String suggestion;

    /** 来源文件（pom.xml / package.json） */
    private String file;

    private Boolean isEol;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
