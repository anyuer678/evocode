package com.evocode.service.impl;

import com.evocode.dto.evolution.EvolutionResp;
import com.evocode.entity.CommitStat;
import com.evocode.entity.FileChangeStat;
import com.evocode.entity.Hotspot;
import com.evocode.mapper.CommitStatMapper;
import com.evocode.mapper.FileChangeStatMapper;
import com.evocode.mapper.HotspotMapper;
import com.evocode.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-EV-01~05（P5b）：演化落库幂等 / available=false 跳过 / 查询降级 / range 过滤 / 聚合映射。
 */
class EvolutionServiceImplTest {

    private final CommitStatMapper commitStatMapper = mock(CommitStatMapper.class);
    private final FileChangeStatMapper fileChangeStatMapper = mock(FileChangeStatMapper.class);
    private final HotspotMapper hotspotMapper = mock(HotspotMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);

    private final EvolutionServiceImpl service =
            new EvolutionServiceImpl(commitStatMapper, fileChangeStatMapper, hotspotMapper, projectMapper);

    private EvolutionResp sampleResult() {
        return new EvolutionResp(
                true,
                List.of(new EvolutionResp.CommitResp(
                        "abc123", "alice", "a@t.co", "2026-08-10T10:00:00+08:00",
                        40, 0, 1, "feat: x")),
                List.of(new EvolutionResp.TrendResp("2026-08-10", 1, 40, 0)),
                List.of(new EvolutionResp.TopFileResp("src/a.py", 1, 40, 0)),
                List.of(new EvolutionResp.AuthorResp("alice", 1, 40)),
                List.of(new EvolutionResp.HotspotResp("src/a.py", "HIGH",
                        List.of("变更 1 次"), null)));
    }

    @Test
    void replaceUnavailableClearsProjectData() {
        // 审查修订：available=false（确定性非 git）→ 清空该项目全部演化数据
        service.replaceForAnalysis(1L, 10L,
                new EvolutionResp(false, List.of(), List.of(), List.of(), List.of(), List.of()));
        verify(commitStatMapper).delete(any());
        verify(fileChangeStatMapper).delete(any());
        verify(hotspotMapper).delete(any());
        verify(commitStatMapper, never()).insert(any(CommitStat.class));
    }

    @Test
    void replaceNullKeepsExistingData() {
        // 审查修订：analyzer 故障/不可达（null）→ 保留旧数据，不清空
        service.replaceForAnalysis(1L, 10L, null);
        verify(commitStatMapper, never()).delete(any());
        verify(fileChangeStatMapper, never()).delete(any());
        verify(hotspotMapper, never()).delete(any());
        verify(commitStatMapper, never()).insert(any(CommitStat.class));
    }

    @Test
    void replaceSkipsWhenCommitsEmpty() {
        service.replaceForAnalysis(1L, 10L, new EvolutionResp(
                true, List.of(), List.of(), List.of(), List.of(), List.of()));
        verify(commitStatMapper, never()).insert(any(CommitStat.class));
    }

    @Test
    void replaceDeletesThenInsertsForAnalysis() {
        service.replaceForAnalysis(1L, 10L, sampleResult());
        // 先删后插（幂等）
        verify(commitStatMapper).delete(any());
        verify(fileChangeStatMapper).delete(any());
        verify(hotspotMapper).delete(any());
        verify(commitStatMapper).insert(any(CommitStat.class));
        verify(fileChangeStatMapper).insert(any(FileChangeStat.class));
        verify(hotspotMapper).insert(any(Hotspot.class));
    }

    @Test
    void getReturnsUnavailableWhenNoData() {
        when(projectMapper.selectById(1L)).thenReturn(new com.evocode.entity.Project());
        when(commitStatMapper.selectOne(any())).thenReturn(null);
        EvolutionResp resp = service.getForProject(1L, "30d");
        assertFalse(resp.available());
        assertTrue(resp.commits().isEmpty());
    }

    @Test
    void getThrowsNotFoundWhenProjectMissing() {
        when(projectMapper.selectById(1L)).thenReturn(null);
        assertThrows(com.evocode.common.BusinessException.class, () -> service.getForProject(1L, "30d"));
    }

    @Test
    void getMapsAggregatesAndHotspots() {
        when(projectMapper.selectById(1L)).thenReturn(new com.evocode.entity.Project());
        CommitStat latest = new CommitStat();
        latest.setProjectId(1L);
        latest.setAnalysisId(10L);
        latest.setCommittedAt(OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
        when(commitStatMapper.selectOne(any())).thenReturn(latest);
        when(commitStatMapper.selectList(any())).thenReturn(List.of(latest));
        when(commitStatMapper.selectTrendByWeek(eq(10L), any())).thenReturn(
                List.of(Map.of("week", "2026-08-10", "commits", 1L, "lines_added", 40L, "lines_removed", 0L)));
        when(commitStatMapper.selectAuthors(eq(10L), any())).thenReturn(
                List.of(Map.of("author_name", "alice", "commits", 1L, "lines_added", 40L)));
        when(fileChangeStatMapper.selectList(any())).thenReturn(
                List.of(fileChangeStat("src/a.py")));
        when(hotspotMapper.selectList(any())).thenReturn(List.of(hotspot("src/a.py", "HIGH")));

        EvolutionResp resp = service.getForProject(1L, "30d");

        assertTrue(resp.available());
        assertEquals(1, resp.trend().size());
        assertEquals("2026-08-10", resp.trend().get(0).week());
        assertEquals(3, resp.topFiles().get(0).commitCount());
        assertEquals("alice", resp.authors().get(0).authorName());
        assertEquals("HIGH", resp.hotspots().get(0).riskLevel());
        assertEquals(1, resp.commits().size());
    }

    @Test
    void getParsesAllRangeWithoutCutoff() {
        when(projectMapper.selectById(1L)).thenReturn(new com.evocode.entity.Project());
        CommitStat latest = new CommitStat();
        latest.setProjectId(1L);
        latest.setAnalysisId(10L);
        latest.setCommittedAt(OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
        when(commitStatMapper.selectOne(any())).thenReturn(latest);
        when(commitStatMapper.selectList(any())).thenReturn(List.of(latest));
        when(commitStatMapper.selectTrendByWeek(eq(10L), any())).thenReturn(List.of());
        when(commitStatMapper.selectAuthors(eq(10L), any())).thenReturn(List.of());
        when(fileChangeStatMapper.selectList(any())).thenReturn(List.of());
        when(hotspotMapper.selectList(any())).thenReturn(List.of());

        EvolutionResp resp = service.getForProject(1L, "all");
        assertTrue(resp.available());
        // all → since = 1970-01-01（无截止）；此处仅验证不抛异常且 available
        assertTrue(resp.trend().isEmpty());
    }

    private FileChangeStat fileChangeStat(String path) {
        FileChangeStat f = new FileChangeStat();
        f.setFilePath(path);
        f.setCommitCount(3);
        f.setLinesAdded(120);
        f.setLinesRemoved(10);
        return f;
    }

    private Hotspot hotspot(String module, String level) {
        Hotspot h = new Hotspot();
        h.setModule(module);
        h.setRiskLevel(level);
        h.setEvidence(List.of("变更 3 次"));
        return h;
    }
}
