package com.evocode.controller;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.PageResultResp;
import com.evocode.common.Result;
import com.evocode.dto.analysis.AnalysisCreateReq;
import com.evocode.dto.analysis.AnalysisHistoryResp;
import com.evocode.dto.analysis.AnalysisResp;
import com.evocode.dto.analysis.AnalysisStatusResp;
import com.evocode.dto.analysis.ReportDetailResp;
import com.evocode.service.analysis.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析任务（docs/06-API契约.md §3.5/3.6）。
 */
@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** 发起分析：202 接受（异步执行，前端轮询 GET /analyses/{id}）。 */
    @PostMapping("/projects/{id}/analyses")
    public ResponseEntity<Result<AnalysisResp>> create(@PathVariable Long id,
                                                       @RequestBody(required = false) AnalysisCreateReq req) {
        String type = req == null ? null : req.type();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.ok(analysisService.create(id, type)));
    }

    /** 分析历史（分页）。 */
    @GetMapping("/projects/{id}/analyses")
    public Result<PageResultResp<AnalysisHistoryResp>> history(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID, null);
        }
        return Result.ok(analysisService.history(id, page, size));
    }

    /** 单任务状态轮询（2s 间隔）。 */
    @GetMapping("/analyses/{id}")
    public Result<AnalysisStatusResp> status(@PathVariable Long id) {
        return Result.ok(analysisService.status(id));
    }

    /** 报告详情（§3.7）。 */
    @GetMapping("/analyses/{id}/report")
    public Result<ReportDetailResp> report(@PathVariable Long id) {
        return Result.ok(analysisService.report(id));
    }

    /** 重新生成报告（§3.7 regenerate）：202 接受，前端轮询状态与报告。 */
    @PostMapping("/analyses/{id}/report/regenerate")
    public ResponseEntity<Result<AnalysisStatusResp>> regenerate(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.ok(analysisService.regenerate(id)));
    }
}
