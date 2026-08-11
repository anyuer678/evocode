package com.evocode.service.debt;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.dto.debt.TechDebtResp;

import java.util.Map;

/**
 * 技术债（06 §3.12）：分析聚合 + 列表 + 状态机。
 */
public interface TechDebtService {

    /** 列表（分页，status 可选筛选，按 createdAt desc）。 */
    IPage<TechDebtResp> list(Long projectId, String status, int page, int size);

    /** 更新状态（06 §3.12 状态机：OPEN→DOING/DONE/WONTFIX、DOING→DONE）。 */
    void updateStatus(Long id, String status, String resolveNote, String wonfixReason);

    /**
     * 分析完成后聚合生成（AD-P7-1）：ARCH/QUALITY/EVOLUTION 从库内产物提取，
     * DEPEND 从 report_json.risks（EOL/依赖关键词）提取；同 analysis 幂等重建。
     */
    void rebuildForAnalysis(Long projectId, Long analysisId, Map<String, Object> reportJson);
}
