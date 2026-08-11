package com.evocode.service;

import com.evocode.dto.evolution.EvolutionResp;

/** 演化统计结果落库与查询（P5）。 */
public interface EvolutionService {

    /** 同 analysis 先删后插（幂等重跑）；available=false 或 commits 空 → 跳过。 */
    void replaceForAnalysis(Long projectId, Long analysisId, EvolutionResp result);

    /** 查询项目演化（range: 30d/90d/180d/all，默认 30d）；无数据 → available=false。 */
    EvolutionResp getForProject(Long projectId, String range);
}
