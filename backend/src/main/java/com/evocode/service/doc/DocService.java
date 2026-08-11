package com.evocode.service.doc;

import com.evocode.dto.doc.DocResp;

import java.util.List;

/**
 * 生成文档（06 §3.14 / §5.9）。
 */
public interface DocService {

    /** 项目文档列表（docType 可选筛选，README/ARCH/API）。 */
    List<DocResp> list(Long projectId, String docType);

    /**
     * 生成/重新生成指定类型文档（调 analyzer 落库；同类型 upsert，version 递增）。
     * edited=true 的文档需 force=true 才会覆盖（审查 M4：后端强制保护）。
     */
    DocResp generate(Long projectId, String docType, boolean force);

    /** 人工编辑（version+1、edited=true）。 */
    DocResp edit(Long docId, String content);
}
