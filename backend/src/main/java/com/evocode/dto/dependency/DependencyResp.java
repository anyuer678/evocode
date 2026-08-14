package com.evocode.dto.dependency;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 依赖清单响应（06 §3.14 / analyzer 05.10，P9d）。
 * available=false 表示无 Maven/npm 依赖文件（非 404）。
 */
public record DependencyResp(boolean available, List<ItemResp> dependencies) {

    public record ItemResp(String name, String version, String type, String file,
                           String risk, String reason, String latest, boolean isEol,
                           String suggestion) {
    }
}
