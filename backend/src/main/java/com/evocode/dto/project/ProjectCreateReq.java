package com.evocode.dto.project;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建项目请求（06 §3.1 方式 B：GitHub）。 */
@Data
public class ProjectCreateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 100, message = "长度不能超过 100")
    private String name;

    private String description;

    @NotBlank(message = "不能为空")
    private String repoUrl;

    /** 0=全量克隆；>0 为 --depth 值（默认 1） */
    @Min(value = 0, message = "非法")
    private Integer cloneDepth = 1;
}
