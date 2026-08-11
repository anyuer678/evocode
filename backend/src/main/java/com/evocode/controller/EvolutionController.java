package com.evocode.controller;

import com.evocode.common.Result;
import com.evocode.dto.evolution.EvolutionResp;
import com.evocode.service.EvolutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 演化统计查询（docs/06-API契约.md §3.13）。 */
@RestController
@RequestMapping("/api/v1")
public class EvolutionController {

    private final EvolutionService evolutionService;

    public EvolutionController(EvolutionService evolutionService) {
        this.evolutionService = evolutionService;
    }

    /** 项目演化：趋势/TOP 文件/作者/热点。range=30d/90d/180d/all。非 Git 或无数据 → available=false。 */
    @GetMapping("/projects/{id}/evolution")
    public Result<EvolutionResp> getEvolution(@PathVariable Long id,
                                              @RequestParam(defaultValue = "30d") String range) {
        return Result.ok(evolutionService.getForProject(id, range));
    }
}
