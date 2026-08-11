package com.evocode.dto.evolution;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 演化统计响应（06 §3.13 + analyzer 05.6）。
 * available=false 表示非 Git 来源 / 无演化数据（非 404，P5 决策 1）。
 */
public record EvolutionResp(boolean available,
                            List<CommitResp> commits,
                            List<TrendResp> trend,
                            List<TopFileResp> topFiles,
                            List<AuthorResp> authors,
                            List<HotspotResp> hotspots) {

    public record CommitResp(String hash, String authorName, String authorEmail,
                             String committedAt, Integer linesAdded, Integer linesRemoved,
                             Integer filesChanged, String message) {
    }

    public record TrendResp(String week, Integer commits, Integer linesAdded, Integer linesRemoved) {
    }

    public record TopFileResp(String filePath, Integer commitCount, Integer linesAdded,
                              Integer linesRemoved) {
    }

    public record AuthorResp(String authorName, Integer commits, Integer linesAdded) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HotspotResp(String module, String riskLevel, List<String> evidence,
                              String aiConclusion) {
    }
}
