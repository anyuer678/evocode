package com.evocode.service.impl;

import com.evocode.common.BusinessException;
import com.evocode.dto.architecture.ArchResultResp;
import com.evocode.dto.architecture.ArchitectureResp;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.ArchitectureEdge;
import com.evocode.entity.ArchitectureNode;
import com.evocode.mapper.ArchViolationMapper;
import com.evocode.mapper.ArchitectureEdgeMapper;
import com.evocode.mapper.ArchitectureNodeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P4b：架构结果先删后插落库 + 查询语义（最新分析/无数据 2010）。 */
class ArchitectureServiceImplTest {

    private final ArchitectureNodeMapper nodeMapper = mock(ArchitectureNodeMapper.class);
    private final ArchitectureEdgeMapper edgeMapper = mock(ArchitectureEdgeMapper.class);
    private final ArchViolationMapper violationMapper = mock(ArchViolationMapper.class);
    private final ArchitectureServiceImpl service =
            new ArchitectureServiceImpl(nodeMapper, edgeMapper, violationMapper);

    private ArchResultResp sampleResult() {
        return new ArchResultResp(
                List.of(
                        new ArchResultResp.Node("c1", "UserController", "CONTROLLER", "c.java", Map.of()),
                        new ArchResultResp.Node("s1", "UserService", "SERVICE", "s.java", Map.of("inDegree", 1))),
                List.of(new ArchResultResp.Edge("c1", "s1", "CALL")),
                List.of(new ArchResultResp.Violation("LAYER_VIOLATION", "Controller 直连 Repository",
                        "c1", "s1", "HIGH", "改为经 Service 调用")));
    }

    @Test
    void replaceForAnalysisPersistsNodesEdgesViolations() {
        // 节点插入后回填自增 id（1、2）
        AtomicLong seq = new AtomicLong();
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, ArchitectureNode.class).setId(seq.incrementAndGet());
            return 1;
        }).when(nodeMapper).insert(org.mockito.ArgumentMatchers.<ArchitectureNode>any());

        service.replaceForAnalysis(7L, 10L, sampleResult());

        // 先删后插
        verify(nodeMapper).delete(any());
        verify(edgeMapper).delete(any());
        verify(violationMapper).delete(any());
        // 2 节点 + 1 边 + 1 违规（显式 cast 消歧：BaseMapper 3.5.7 有 insert(T)/insert(Collection) 重载）
        verify(nodeMapper, times(2)).insert((ArchitectureNode) org.mockito.ArgumentMatchers.any());
        verify(edgeMapper, times(1)).insert((ArchitectureEdge) org.mockito.ArgumentMatchers.any());
        verify(violationMapper, times(1)).insert((ArchViolation) org.mockito.ArgumentMatchers.any());
        // 边引用节点落库后的 id（nodeKey 映射）
        verify(edgeMapper).insert((ArchitectureEdge) org.mockito.ArgumentMatchers.argThat(
                (ArchitectureEdge e) -> e.getSourceNodeId() == 1L && e.getTargetNodeId() == 2L
                        && "CALL".equals(e.getRelation())
                        && e.getProjectId() == 7L && e.getAnalysisId() == 10L));
    }

    @Test
    void replaceForAnalysisEmptySkipsEverything() {
        ArchResultResp empty = new ArchResultResp(List.of(), List.of(), List.of());

        service.replaceForAnalysis(7L, 10L, empty);

        verify(nodeMapper, never()).delete(any());
        verify(nodeMapper, never()).insert((ArchitectureNode) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getForProjectWithoutAnalysisIdUsesLatestArchAnalysis() {
        ArchitectureNode latest = new ArchitectureNode();
        latest.setId(9L);
        latest.setAnalysisId(20L);
        latest.setNodeKey("c1");
        latest.setName("UserController");
        latest.setNodeType("CONTROLLER");
        latest.setFilePath("c.java");
        latest.setMetrics(Map.of());
        when(nodeMapper.selectOne(any())).thenReturn(latest);
        when(nodeMapper.selectList(any())).thenReturn(List.of(latest));
        when(edgeMapper.selectList(any())).thenReturn(List.of());
        when(violationMapper.selectList(any())).thenReturn(List.of());

        ArchitectureResp resp = service.getForProject(7L, null);

        assertNotNull(resp);
        assertEquals(1, resp.nodes().size());
        assertEquals(9L, resp.nodes().get(0).id());
        assertEquals("CONTROLLER", resp.nodes().get(0).nodeType());
    }

    @Test
    void getForProjectWithoutAnyDataThrows2010() {
        when(nodeMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getForProject(7L, null));
        assertEquals(2010, e.getCode());
    }

    @Test
    void getForProjectWithGivenAnalysisIdButNoNodesThrows2010() {
        when(nodeMapper.selectList(any())).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getForProject(7L, 99L));
        assertEquals(2010, e.getCode());
    }
}
