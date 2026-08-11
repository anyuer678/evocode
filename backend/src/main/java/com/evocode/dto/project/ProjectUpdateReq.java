package com.evocode.dto.project;

/**
 * 项目更新请求（06 §3.2 PATCH，P9b）：name/description 均可选，至少提供一个。
 */
public record ProjectUpdateReq(String name, String description) {

    public boolean isEmpty() {
        return (name == null || name.isBlank()) && (description == null || description.isBlank());
    }
}
