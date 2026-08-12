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
import com.evocode.dto.analysis.ReportHistoryListResp;
import com.evocode.dto.analysis.ReportHistoryResp;
import com.evocode.service.analysis.AnalysisProgressPublisher;
import com.evocode.service.analysis.AnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 分析任务（docs/06-API契约.md §3.5/3.6）。
 */
@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final AnalysisProgressPublisher progressPublisher;

    public AnalysisController(AnalysisService analysisService,
                              AnalysisProgressPublisher progressPublisher) {
        this.analysisService = analysisService;
        this.progressPublisher = progressPublisher;
    }

    /** 发起分析：202 接受（异步执行，前端轮询 GET /analyses/{id}）。 */
    @PostMapping("/projects/{id}/analyses")
    public ResponseEntity<Result<AnalysisResp>> create(@PathVariable Long id,
                                                       @RequestBody(required = false) AnalysisCreateReq req) {
        String type = req == null ? null : req.type();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.ok(analysisService.create(id, type)));
    }

    /** 分析进度 SSE（P9e E1）：EventSource 订阅项目分析进度，状态机变更点推送。 */
    @GetMapping(value = "/projects/{id}/analyses/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progressEvents(@PathVariable Long id,
                                     jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no"); // 契约 §4.3：禁用中间层缓冲
        return progressPublisher.subscribe(id);
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

    /** 报告历史（P9c）：聚合 SUCCEEDED + report_json 摘要，limit 默认 10 上限 20。 */
    @GetMapping("/projects/{id}/report/history")
    public Result<ReportHistoryListResp> reportHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 20) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID, "limit 须在 1~20");
        }
        return Result.ok(new ReportHistoryListResp(analysisService.reportHistory(id, limit)));
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
