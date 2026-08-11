package com.evocode.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode ec, String detail) {
        super(ec.getMessage() + (detail == null || detail.isBlank() ? "" : "：" + detail));
        this.code = ec.getCode();
    }
}
