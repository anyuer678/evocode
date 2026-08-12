package com.evocode.service.dependency;

import com.evocode.dto.dependency.DependencyResp;

/**
 * 依赖清单落库与查询（P9d，06 §3.14）。
 */
public interface DependencyService {

    /** 以 analysisId 为单位替换项目依赖数据；available=false 清空该项目。 */
    void replaceForAnalysis(Long projectId, Long analysisId, DependencyResp result);

    /** 查询项目最新分析的依赖清单（无数据 → available=false）。 */
    DependencyResp getForProject(Long projectId);
}
