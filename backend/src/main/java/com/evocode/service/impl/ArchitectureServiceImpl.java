package com.evocode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.architecture.ArchResultResp;
import com.evocode.dto.architecture.ArchitectureResp;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.ArchitectureEdge;
import com.evocode.entity.ArchitectureNode;
import com.evocode.mapper.ArchViolationMapper;
import com.evocode.mapper.ArchitectureEdgeMapper;
import com.evocode.mapper.ArchitectureNodeMapper;
import com.evocode.service.ArchitectureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 架构分析结果落库与查询。 */
@Slf4j
@Service
public class ArchitectureServiceImpl implements ArchitectureService {

    private final ArchitectureNodeMapper nodeMapper;
    private final ArchitectureEdgeMapper edgeMapper;
    private final ArchViolationMapper violationMapper;

    public ArchitectureServiceImpl(ArchitectureNodeMapper nodeMapper, ArchitectureEdgeMapper edgeMapper,
                                   ArchViolationMapper violationMapper) {
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.violationMapper = violationMapper;
    }

    @Override
    @Transactional
    public void replaceForAnalysis(Long projectId, Long analysisId, ArchResultResp result) {
        if (result == null || result.nodes() == null || result.nodes().isEmpty()) {
            log.warn("架构结果为空，跳过落库 analysisId={}", analysisId);
            return;
        }
        // 先删后插（同一分析重跑幂等）
        nodeMapper.delete(new LambdaQueryWrapper<ArchitectureNode>()
                .eq(ArchitectureNode::getAnalysisId, analysisId));
        edgeMapper.delete(new LambdaQueryWrapper<ArchitectureEdge>()
                .eq(ArchitectureEdge::getAnalysisId, analysisId));
        violationMapper.delete(new LambdaQueryWrapper<ArchViolation>()
                .eq(ArchViolation::getAnalysisId, analysisId));

        // 1) 节点落库，nodeKey -> id
        Map<String, Long> nodeIdByKey = new HashMap<>();
        for (ArchResultResp.Node n : result.nodes()) {
            ArchitectureNode node = new ArchitectureNode();
            node.setProjectId(projectId);
            node.setAnalysisId(analysisId);
            node.setNodeKey(n.nodeKey());
            node.setName(n.name());
            node.setNodeType(n.nodeType());
            node.setFilePath(n.filePath());
            node.setMetrics(n.metrics() != null ? n.metrics() : Map.of());
            nodeMapper.insert(node);
            nodeIdByKey.put(n.nodeKey(), node.getId());
        }

        // 2) 边
        if (result.edges() != null) {
            for (ArchResultResp.Edge e : result.edges()) {
                Long src = nodeIdByKey.get(e.sourceNodeKey());
                Long dst = nodeIdByKey.get(e.targetNodeKey());
                if (src == null || dst == null) {
                    log.warn("架构边引用未知节点，跳过 {} -> {} (analysisId={})",
                            e.sourceNodeKey(), e.targetNodeKey(), analysisId);
                    continue;
                }
                ArchitectureEdge edge = new ArchitectureEdge();
                edge.setProjectId(projectId);
                edge.setAnalysisId(analysisId);
                edge.setSourceNodeId(src);
                edge.setTargetNodeId(dst);
                edge.setRelation(e.relation() != null ? e.relation() : "CALL");
                edgeMapper.insert(edge);
            }
        }

        // 3) 违规
        if (result.violations() != null) {
            for (ArchResultResp.Violation v : result.violations()) {
                ArchViolation violation = new ArchViolation();
                violation.setProjectId(projectId);
                violation.setAnalysisId(analysisId);
                violation.setViolationType(v.violationType() != null ? v.violationType() : "UNKNOWN");
                violation.setDescription(v.description());
                violation.setSourceNodeId(nodeIdByKey.get(v.sourceNodeKey()));
                violation.setTargetNodeId(nodeIdByKey.get(v.targetNodeKey()));
                violation.setSeverity(v.severity() != null ? v.severity() : "MEDIUM");
                violation.setSuggestion(v.suggestion());
                violationMapper.insert(violation);
            }
        }
    }

    @Override
    public ArchitectureResp getForProject(Long projectId, Long analysisId) {
        if (analysisId == null) {
            // 直接取架构表最新一次分析，绕开 analysis 状态（最近分析失败时仍可读上次结果）
            ArchitectureNode latest = nodeMapper.selectOne(new LambdaQueryWrapper<ArchitectureNode>()
                    .eq(ArchitectureNode::getProjectId, projectId)
                    .orderByDesc(ArchitectureNode::getAnalysisId)
                    .last("LIMIT 1"));
            analysisId = latest != null ? latest.getAnalysisId() : null;
        }
        if (analysisId == null) {
            throw new BusinessException(ErrorCode.ARCH_NOT_FOUND, "该项目尚无架构分析");
        }
        List<ArchitectureNode> nodes = nodeMapper.selectList(new LambdaQueryWrapper<ArchitectureNode>()
                .eq(ArchitectureNode::getAnalysisId, analysisId));
        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.ARCH_NOT_FOUND, "该项目尚无架构分析");
        }
        List<ArchitectureEdge> edges = edgeMapper.selectList(new LambdaQueryWrapper<ArchitectureEdge>()
                .eq(ArchitectureEdge::getAnalysisId, analysisId));
        List<ArchViolation> violations = violationMapper.selectList(new LambdaQueryWrapper<ArchViolation>()
                .eq(ArchViolation::getAnalysisId, analysisId));

        return new ArchitectureResp(
                nodes.stream().map(n -> new ArchitectureResp.NodeResp(
                        n.getId(), n.getNodeKey(), n.getName(), n.getNodeType(),
                        n.getFilePath(), n.getMetrics())).toList(),
                edges.stream().map(e -> new ArchitectureResp.EdgeResp(
                        e.getId(), e.getSourceNodeId(), e.getTargetNodeId(), e.getRelation())).toList(),
                violations.stream().map(v -> new ArchitectureResp.ViolationResp(
                        v.getId(), v.getViolationType(), v.getDescription(),
                        v.getSourceNodeId(), v.getTargetNodeId(),
                        v.getSeverity(), v.getSuggestion(), v.getAiNote())).toList());
    }
}
