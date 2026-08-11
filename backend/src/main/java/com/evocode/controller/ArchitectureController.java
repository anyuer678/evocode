package com.evocode.controller;

import com.evocode.common.Result;
import com.evocode.dto.architecture.ArchitectureResp;
import com.evocode.service.ArchitectureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 架构分析查询（docs/06-API契约.md §3.11）。 */
@RestController
@RequestMapping("/api/v1")
public class ArchitectureController {

    private final ArchitectureService architectureService;

    public ArchitectureController(ArchitectureService architectureService) {
        this.architectureService = architectureService;
    }

    /** 项目架构：节点/边/违规。analysisId 缺省取最新一次架构分析。无数据 404(2010)。 */
    @GetMapping("/projects/{id}/architecture")
    public Result<ArchitectureResp> getArchitecture(@PathVariable Long id,
                                                    @RequestParam(required = false) Long analysisId) {
        return Result.ok(architectureService.getForProject(id, analysisId));
    }
}
