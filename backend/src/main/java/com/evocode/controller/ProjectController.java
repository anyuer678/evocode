package com.evocode.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.PageResultResp;
import com.evocode.common.Result;
import com.evocode.dto.project.ProjectCreateReq;
import com.evocode.dto.project.ProjectDetailResp;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.dto.project.ProjectUpdateReq;
import com.evocode.service.project.ProjectService;
import com.evocode.service.project.ReportExportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目管理（06 §3.1~3.4）。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ReportExportService reportExportService;

    public ProjectController(ProjectService projectService,
                             ReportExportService reportExportService) {
        this.projectService = projectService;
        this.reportExportService = reportExportService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<ProjectResp>> createFromZip(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam("file") MultipartFile file) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "name 不能为空");
        }
        if (name.length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "name 长度不能超过 100");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.ok(projectService.createFromZip(name, description, file)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result<ProjectResp>> createFromGit(
            @Valid @RequestBody ProjectCreateReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.ok(projectService.createFromGit(req.getName(), req.getDescription(),
                        req.getRepoUrl(), req.getCloneDepth())));
    }

    @GetMapping
    public Result<PageResultResp<ProjectSummaryResp>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID, null);
        }
        PageResultResp<ProjectSummaryResp> paged = PageResultResp.of(
                projectService.list(page, size, keyword, language, status, sort, order));
        return Result.ok(paged);
    }

    @GetMapping("/{id}")
    public Result<ProjectDetailResp> detail(@PathVariable Long id) {
        return Result.ok(projectService.detail(id));
    }

    /** P9b：报告导出（06 §3.7 扩展）——Markdown 纯文本下载。 */
    @GetMapping(value = "/{id}/report/export", produces = "text/markdown; charset=utf-8")
    public ResponseEntity<byte[]> exportReport(@PathVariable Long id) {
        String markdown = reportExportService.exportLatest(id);
        String filename = "evocode-report-" + id + ".md";
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + filename + "\"")
                .body(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @PatchMapping("/{id}")
    public Result<ProjectResp> update(@PathVariable Long id,
                                      @RequestBody ProjectUpdateReq req) {
        return Result.ok(projectService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok(null);
    }
}
