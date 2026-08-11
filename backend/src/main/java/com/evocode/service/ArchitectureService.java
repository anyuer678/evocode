package com.evocode.service;

import com.evocode.dto.architecture.ArchResultResp;
import com.evocode.dto.architecture.ArchitectureResp;

public interface ArchitectureService {

    /**
     * 以 analysisId 为单位整体替换架构结果（先删后插）。
     * 节点先落库取得自增 id，边/违规再以 nodeKey 映射为 source/targetNodeId。
     */
    void replaceForAnalysis(Long projectId, Long analysisId, ArchResultResp result);

    /**
     * 查询项目架构；analysisId 为空时取该项目最新一次分析。
     * 无架构数据时抛 2001。
     */
    ArchitectureResp getForProject(Long projectId, Long analysisId);
}
