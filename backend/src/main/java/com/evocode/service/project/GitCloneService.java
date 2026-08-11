package com.evocode.service.project;

import java.nio.file.Path;

/**
 * GitHub 仓库克隆（FR-1.2：--depth 1 默认；cloneDepth=0 全量；超时 5 分钟；GIT_PROXY 支持）。
 */
public interface GitCloneService {

    /**
     * 克隆 repoUrl 到 targetDir。失败抛 BusinessException（1002 格式非法 / 2009 克隆失败）。
     * 超时或失败时清理 targetDir。
     */
    void clone(String repoUrl, int cloneDepth, Path targetDir);
}
