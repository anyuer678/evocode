package com.evocode.controller;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.Result;
import com.evocode.dto.quality.ExplainResp;
import com.evocode.dto.quality.QualityIssuesResp;
import com.evocode.service.quality.QualityIssueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质量 issues（docs/06-API契约.md §3.10）。
 */
@RestController
@RequestMapping("/api/v1")
public class QualityController {

    private final QualityIssueService qualityIssueService;

    public QualityController(QualityIssueService qualityIssueService) {
        this.qualityIssueService = qualityIssueService;
    }

    /** 分页查询质量 issues + 指标聚合。 */
    @GetMapping("/projects/{id}/quality-issues")
    public Result<QualityIssuesResp> query(
            @PathVariable Long id,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID, null);
        }
        return Result.ok(qualityIssueService.query(id, severity, kind, status, page, size));
    }

    /** 重新解释 issue（06 §3.10，TD-01 契约补齐）：analyzer 规则版 + LLM 增强，回填 ai_* 字段。 */
    @PostMapping("/quality-issues/{issueId}/explain")
    public Result<ExplainResp> explain(@PathVariable Long issueId) {
        return Result.ok(qualityIssueService.explain(issueId));
    }
}
