package com.evocode.controller;

import com.evocode.common.Result;
import com.evocode.dto.dependency.DependencyResp;
import com.evocode.service.dependency.DependencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 依赖清单查询（docs/06-API契约.md §3.14，P9d）。 */
@RestController
@RequestMapping("/api/v1")
public class DependencyController {

    private final DependencyService dependencyService;

    public DependencyController(DependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    /** 项目依赖：EOL 判定清单。无 Maven/npm 依赖文件 → available=false。 */
    @GetMapping("/projects/{id}/dependencies")
    public Result<DependencyResp> getDependencies(@PathVariable Long id) {
        return Result.ok(dependencyService.getForProject(id));
    }
}
