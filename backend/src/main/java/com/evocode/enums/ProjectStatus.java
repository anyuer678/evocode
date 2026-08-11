package com.evocode.enums;

/** 建档状态（07 §4）。 */
public enum ProjectStatus {
    CREATED, ANALYZING, READY, FAILED;

    /** 安全解析：非法返回 null（用于列表筛选校验）。 */
    public static ProjectStatus valueOfSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ProjectStatus s : values()) {
            if (s.name().equalsIgnoreCase(value.trim())) {
                return s;
            }
        }
        return null;
    }
}
