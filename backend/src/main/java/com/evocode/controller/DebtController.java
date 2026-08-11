package com.evocode.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.PageResultResp;
import com.evocode.common.Result;
import com.evocode.dto.debt.TechDebtResp;
import com.evocode.dto.debt.TechDebtStatusReq;
import com.evocode.service.debt.TechDebtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 技术债（06 §3.12）。
 */
@RestController
@RequestMapping("/api/v1")
public class DebtController {

    private final TechDebtService techDebtService;

    public DebtController(TechDebtService techDebtService) {
        this.techDebtService = techDebtService;
    }

    @GetMapping("/projects/{projectId}/tech-debts")
    public Result<PageResultResp<TechDebtResp>> list(
            @PathVariable Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID, "分页参数非法");
        }
        IPage<TechDebtResp> result = techDebtService.list(projectId, status, page, size);
        return Result.ok(PageResultResp.of(result));
    }

    @PostMapping("/tech-debts/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @RequestBody TechDebtStatusReq req) {
        if (req.status() == null || req.status().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "status 必填");
        }
        techDebtService.updateStatus(id, req.status(), req.resolveNote(), req.wonfixReason());
        return Result.ok(null);
    }
}
