package com.evocode.service.doc;

import com.evocode.dto.doc.DocResp;

import java.util.List;

/**
 * 生成文档（06 §3.14 / §5.9）。
 */
public interface DocService {

    /** 项目全部文档（README/ARCH/API 三类，不分页）。 */
    List<DocResp> list(Long projectId);

    /** 生成/重新生成指定类型文档（调 analyzer 落库；同类型 upsert，version 递增）。 */
    DocResp generate(Long projectId, String docType);

    /** 人工编辑（version+1、edited=true）。 */
    DocResp edit(Long docId, String content);
}
