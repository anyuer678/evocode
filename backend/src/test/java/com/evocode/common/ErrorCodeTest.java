package com.evocode.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void codeRangesMatchContract() {
        // 06 §2.2：1xxx 参数 / 2xxx 业务 / 3xxx 分析器 / 5xxx 系统
        assertThat(ErrorCode.PARAM_INVALID.getCode()).isEqualTo(1002);
        assertThat(ErrorCode.PROJECT_NOT_FOUND.getCode()).isEqualTo(2001);
        assertThat(ErrorCode.ANALYZER_UNREACHABLE.getCode()).isEqualTo(3001);
        assertThat(ErrorCode.DISK_FULL.getCode()).isEqualTo(5001);
        assertThat(ErrorCode.PROJECT_NOT_FOUND.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.ANALYZER_UNREACHABLE.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
    }

    @Test
    void noDuplicateCodes() {
        long unique = java.util.Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).distinct().count();
        assertThat(unique).isEqualTo(ErrorCode.values().length);
    }
}
