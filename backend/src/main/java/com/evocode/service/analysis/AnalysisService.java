package com.evocode.service.analysis;

import com.evocode.common.PageResultResp;
import com.evocode.dto.analysis.AnalysisHistoryResp;
import com.evocode.dto.analysis.AnalysisResp;
import com.evocode.dto.analysis.AnalysisStatusResp;
import com.evocode.dto.analysis.ReportDetailResp;

/**
 * 分析任务管理（docs/06-API契约.md §3.5/3.6/3.7）。
 * v0.1 仅支持 FULL；P3+ 扩展 QUALITY/ARCH/EVOLUTION。
 */
public interface AnalysisService {

    /** 发起分析：校验项目/类型/排他后落 PENDING 记录并异步执行。 */
    AnalysisResp create(Long projectId, String type);

    /** 分析历史（分页，按 id 倒序）。 */
    PageResultResp<AnalysisHistoryResp> history(Long projectId, int page, int size);

    /** 单任务状态轮询（前端 2s 间隔）。 */
    AnalysisStatusResp status(Long analysisId);

    /** 报告详情（§3.7）；分析不存在或无报告 → 2001。 */
    ReportDetailResp report(Long analysisId);

    /** 重新生成报告（§3.7 regenerate）：不重扫，仅重跑报告步骤；返回 202 轮询体。 */
    AnalysisStatusResp regenerate(Long analysisId);
}
