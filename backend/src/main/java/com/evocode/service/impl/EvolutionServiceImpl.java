package com.evocode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.evolution.EvolutionResp;
import com.evocode.entity.CommitStat;
import com.evocode.entity.FileChangeStat;
import com.evocode.entity.Hotspot;
import com.evocode.entity.Project;
import com.evocode.mapper.CommitStatMapper;
import com.evocode.mapper.FileChangeStatMapper;
import com.evocode.mapper.HotspotMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.EvolutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** 演化统计结果落库与查询（P5b）。 */
@Slf4j
@Service
public class EvolutionServiceImpl implements EvolutionService {

    private static final Map<String, Integer> RANGES =
            Map.of("30d", 30, "90d", 90, "180d", 180, "all", 0);
    private static final int TOP_FILES = 10;

    private final CommitStatMapper commitStatMapper;
    private final FileChangeStatMapper fileChangeStatMapper;
    private final HotspotMapper hotspotMapper;
    private final ProjectMapper projectMapper;

    public EvolutionServiceImpl(CommitStatMapper commitStatMapper,
                                FileChangeStatMapper fileChangeStatMapper,
                                HotspotMapper hotspotMapper,
                                ProjectMapper projectMapper) {
        this.commitStatMapper = commitStatMapper;
        this.fileChangeStatMapper = fileChangeStatMapper;
        this.hotspotMapper = hotspotMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    @Transactional
    public void replaceForAnalysis(Long projectId, Long analysisId, EvolutionResp result) {
        if (result == null) {
            // analyzer 故障/不可达（null）→ 保留旧数据（审查：分析异常不清空）
            log.info("演化结果为空，跳过 projectId={} analysisId={}", projectId, analysisId);
            return;
        }
        if (!result.available() || result.commits() == null || result.commits().isEmpty()) {
            // 非 git 仓库（available=false）：清空该项目全部演化数据，避免旧分析残留
            // 误导（zip 项目显示父仓库历史的端到端实测）。仅确定性'非 git'才清空。
            log.info("演化不可用，清空项目演化数据 projectId={} analysisId={}",
                    projectId, analysisId);
            commitStatMapper.delete(new LambdaQueryWrapper<CommitStat>()
                    .eq(CommitStat::getProjectId, projectId));
            fileChangeStatMapper.delete(new LambdaQueryWrapper<FileChangeStat>()
                    .eq(FileChangeStat::getProjectId, projectId));
            hotspotMapper.delete(new LambdaQueryWrapper<Hotspot>()
                    .eq(Hotspot::getProjectId, projectId));
            return;
        }
        // 先删后插（同一分析重跑幂等）
        commitStatMapper.delete(new LambdaQueryWrapper<CommitStat>()
                .eq(CommitStat::getAnalysisId, analysisId));
        fileChangeStatMapper.delete(new LambdaQueryWrapper<FileChangeStat>()
                .eq(FileChangeStat::getAnalysisId, analysisId));
        hotspotMapper.delete(new LambdaQueryWrapper<Hotspot>()
                .eq(Hotspot::getAnalysisId, analysisId));

        for (EvolutionResp.CommitResp c : result.commits()) {
            CommitStat row = new CommitStat();
            row.setProjectId(projectId);
            row.setAnalysisId(analysisId);
            row.setCommitHash(c.hash());
            row.setAuthorName(c.authorName());
            row.setAuthorEmail(c.authorEmail());
            row.setCommittedAt(OffsetDateTime.parse(c.committedAt()));
            row.setLinesAdded(c.linesAdded());
            row.setLinesRemoved(c.linesRemoved());
            row.setFilesChanged(c.filesChanged());
            row.setMessage(c.message());
            commitStatMapper.insert(row);
        }
        if (result.topFiles() != null) {
            for (EvolutionResp.TopFileResp tf : result.topFiles()) {
                FileChangeStat row = new FileChangeStat();
                row.setProjectId(projectId);
                row.setAnalysisId(analysisId);
                row.setFilePath(tf.filePath());
                row.setCommitCount(tf.commitCount());
                row.setLinesAdded(tf.linesAdded());
                row.setLinesRemoved(tf.linesRemoved());
                fileChangeStatMapper.insert(row);
            }
        }
        if (result.hotspots() != null) {
            for (EvolutionResp.HotspotResp h : result.hotspots()) {
                Hotspot row = new Hotspot();
                row.setProjectId(projectId);
                row.setAnalysisId(analysisId);
                row.setModule(h.module());
                row.setRiskLevel(h.riskLevel());
                row.setEvidence(h.evidence());
                row.setAiConclusion(h.aiConclusion());
                hotspotMapper.insert(row);
            }
        }
    }

    @Override
    public EvolutionResp getForProject(Long projectId, String range) {
        // 06 §3.13：项目不存在 → 404/2001（区别于「非 Git 来源」的 available=false）
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        // 直接取演化表最新一次分析，绕开 analysis 状态（最近分析失败时仍可读上次结果）
        CommitStat latest = commitStatMapper.selectOne(new LambdaQueryWrapper<CommitStat>()
                .eq(CommitStat::getProjectId, projectId)
                .orderByDesc(CommitStat::getAnalysisId)
                .last("LIMIT 1"));
        if (latest == null) {
            return new EvolutionResp(false, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Long analysisId = latest.getAnalysisId();
        Integer days = RANGES.getOrDefault(range == null ? "30d" : range.toLowerCase(), 30);
        OffsetDateTime since = days > 0
                ? OffsetDateTime.now().minusDays(days)
                : OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        List<EvolutionResp.CommitResp> commits = commitStatMapper.selectList(
                        new LambdaQueryWrapper<CommitStat>()
                                .eq(CommitStat::getAnalysisId, analysisId)
                                .ge(CommitStat::getCommittedAt, since)
                                .orderByDesc(CommitStat::getCommittedAt))
                .stream()
                .map(c -> new EvolutionResp.CommitResp(
                        c.getCommitHash(), c.getAuthorName(), c.getAuthorEmail(),
                        c.getCommittedAt() != null ? c.getCommittedAt().toString() : null,
                        c.getLinesAdded(), c.getLinesRemoved(), c.getFilesChanged(), c.getMessage()))
                .toList();

        List<EvolutionResp.TrendResp> trend = commitStatMapper.selectTrendByWeek(analysisId, since)
                .stream()
                .map(r -> new EvolutionResp.TrendResp(
                        String.valueOf(r.get("week")),
                        ((Number) r.get("commits")).intValue(),
                        ((Number) r.get("lines_added")).intValue(),
                        ((Number) r.get("lines_removed")).intValue()))
                .toList();

        List<EvolutionResp.TopFileResp> topFiles = fileChangeStatMapper.selectList(
                        new LambdaQueryWrapper<FileChangeStat>()
                                .eq(FileChangeStat::getAnalysisId, analysisId)
                                .orderByDesc(FileChangeStat::getCommitCount)
                                .last("LIMIT " + TOP_FILES))
                .stream()
                .map(f -> new EvolutionResp.TopFileResp(
                        f.getFilePath(), f.getCommitCount(), f.getLinesAdded(), f.getLinesRemoved()))
                .toList();

        List<EvolutionResp.AuthorResp> authors = commitStatMapper.selectAuthors(analysisId, since)
                .stream()
                .map(r -> new EvolutionResp.AuthorResp(
                        String.valueOf(r.get("author_name")),
                        ((Number) r.get("commits")).intValue(),
                        ((Number) r.get("lines_added")).intValue()))
                .toList();

        List<EvolutionResp.HotspotResp> hotspots = hotspotMapper.selectList(
                        new LambdaQueryWrapper<Hotspot>().eq(Hotspot::getAnalysisId, analysisId))
                .stream()
                .map(h -> new EvolutionResp.HotspotResp(
                        h.getModule(), h.getRiskLevel(), h.getEvidence(), h.getAiConclusion()))
                .toList();

        return new EvolutionResp(true, commits, trend, topFiles, authors, hotspots);
    }
}
