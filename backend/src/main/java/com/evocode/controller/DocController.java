package com.evocode.controller;

import com.evocode.common.Result;
import com.evocode.dto.doc.DocEditReq;
import com.evocode.dto.doc.DocResp;
import com.evocode.service.doc.DocService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 生成文档（06 §3.14）。
 */
@RestController
@RequestMapping("/api/v1")
public class DocController {

    private final DocService docService;

    public DocController(DocService docService) {
        this.docService = docService;
    }

    @GetMapping("/projects/{projectId}/docs")
    public Result<List<DocResp>> list(@PathVariable Long projectId) {
        return Result.ok(docService.list(projectId));
    }

    /** 生成/重新生成（同步调 analyzer；edited 文档前端需二次确认）。 */
    @PostMapping("/projects/{projectId}/docs/{docType}/generate")
    public Result<DocResp> generate(@PathVariable Long projectId,
                                    @PathVariable String docType) {
        return Result.ok(docService.generate(projectId, docType));
    }

    @PostMapping("/docs/{id}/edit")
    public Result<DocResp> edit(@PathVariable Long id, @RequestBody DocEditReq req) {
        return Result.ok(docService.edit(id, req.content()));
    }
}
