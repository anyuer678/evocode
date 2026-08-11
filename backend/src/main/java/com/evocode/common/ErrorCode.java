package com.evocode.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码（唯一来源：docs/06-API契约.md §2.2）。
 * 段位：1xxx 参数 / 2xxx 业务 / 3xxx 分析器 / 5xxx 系统。
 */
@Getter
public enum ErrorCode {

    // 1xxx 参数
    PARAM_MISSING(1001, "参数缺失", HttpStatus.BAD_REQUEST),
    PARAM_INVALID(1002, "参数格式错误", HttpStatus.BAD_REQUEST),
    PAGE_PARAM_INVALID(1003, "分页参数非法", HttpStatus.BAD_REQUEST),

    // 2xxx 业务
    PROJECT_NOT_FOUND(2001, "项目不存在", HttpStatus.NOT_FOUND),
    ANALYSIS_BUSY(2002, "该项目已有运行中的分析任务", HttpStatus.BAD_REQUEST),
    FILE_ILLEGAL(2003, "上传文件非法", HttpStatus.BAD_REQUEST),
    PROJECT_STATE_FORBIDDEN(2004, "项目状态不允许该操作", HttpStatus.BAD_REQUEST), // 预留（06 §2.2）
    FILE_CONTENT_FORBIDDEN(2005, "文件内容越权或超限", HttpStatus.BAD_REQUEST),
    SESSION_NOT_FOUND(2006, "会话不存在", HttpStatus.NOT_FOUND),
    CHAT_TOO_FREQUENT(2007, "发送过于频繁/重复提交", HttpStatus.BAD_REQUEST),
    REPORT_REGENERATING(2008, "该分析正在重新生成报告中", HttpStatus.BAD_REQUEST),
    GIT_CLONE_FAILED(2009, "仓库克隆失败", HttpStatus.BAD_REQUEST),
    ARCH_NOT_FOUND(2010, "该项目尚无架构分析", HttpStatus.NOT_FOUND),

    // 3xxx 分析器
    ANALYZER_UNREACHABLE(3001, "分析服务不可达或内部错误", HttpStatus.BAD_GATEWAY),
    SCAN_TIMEOUT(3002, "扫描超时", HttpStatus.BAD_GATEWAY),
    LLM_FAILED(3003, "AI 服务调用失败", HttpStatus.BAD_GATEWAY),

    // 5xxx 系统
    DISK_FULL(5001, "磁盘空间不足", HttpStatus.INTERNAL_SERVER_ERROR),
    DB_ERROR(5002, "数据库异常", HttpStatus.INTERNAL_SERVER_ERROR),
    TASK_INTERRUPTED(5003, "服务重启导致任务中断", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
