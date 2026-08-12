package com.evocode.service.dependency;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.dependency.DependencyResp;
import com.evocode.entity.Dependency;
import com.evocode.entity.Project;
import com.evocode.mapper.DependencyMapper;
import com.evocode.mapper.ProjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 依赖清单落库与查询（P9d，06 §3.14）。
 * 落库语义同 evolution：available=false（无 Maven/npm 依赖文件）清空该项目，
 * 避免旧分析残留误导；analyzer 故障（null）保留旧数据。
 */
@Slf4j
@Service
public class DependencyServiceImpl implements DependencyService {

    private final DependencyMapper dependencyMapper;
    private final ProjectMapper projectMapper;

    public DependencyServiceImpl(DependencyMapper dependencyMapper, ProjectMapper projectMapper) {
        this.dependencyMapper = dependencyMapper;
        this.projectMapper = projectMapper;
    }

    @Override
    @Transactional
    public void replaceForAnalysis(Long projectId, Long analysisId, DependencyResp result) {
        if (result == null) {
            log.info("依赖结果为空，跳过 projectId={} analysisId={}", projectId, analysisId);
            return;
        }
        if (!result.available() || result.dependencies() == null
                || result.dependencies().isEmpty()) {
            log.info("依赖不可用，清空项目依赖数据 projectId={} analysisId={}",
                    projectId, analysisId);
            dependencyMapper.delete(new LambdaQueryWrapper<Dependency>()
                    .eq(Dependency::getProjectId, projectId));
            return;
        }
        // 先删后插（同一分析重跑幂等）
        dependencyMapper.delete(new LambdaQueryWrapper<Dependency>()
                .eq(Dependency::getAnalysisId, analysisId));
        for (DependencyResp.ItemResp item : result.dependencies()) {
            Dependency row = new Dependency();
            row.setProjectId(projectId);
            row.setAnalysisId(analysisId);
            row.setEcosystem(ecosystemOf(item.type()));
            row.setName(item.name());
            row.setVersion(item.version());
            row.setLatestVersion(item.latest());
            // risk=null（未命中规则=未知）原样保留，前端显示"未知版本"而非误报 LOW
            row.setRiskLevel(item.risk());
            row.setRiskReason(item.reason());
            row.setSuggestion(item.reason());
            row.setFile(item.file());
            row.setIsEol(item.isEol());
            dependencyMapper.insert(row);
        }
    }

    @Override
    public DependencyResp getForProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在");
        }
        // 取最新分析的依赖（按 analysis_id 倒序取任意最新一条；dependency 无独立分析时间列）
        Dependency sample = dependencyMapper.selectOne(new LambdaQueryWrapper<Dependency>()
                .eq(Dependency::getProjectId, projectId)
                .orderByDesc(Dependency::getAnalysisId)
                .last("LIMIT 1"));
        if (sample == null) {
            return new DependencyResp(false, List.of());
        }
        List<Dependency> rows = dependencyMapper.selectList(new LambdaQueryWrapper<Dependency>()
                .eq(Dependency::getProjectId, projectId)
                .eq(Dependency::getAnalysisId, sample.getAnalysisId())
                .orderByDesc(Dependency::getId));
        List<DependencyResp.ItemResp> items = rows.stream()
                .map(r -> new DependencyResp.ItemResp(
                        r.getName(), r.getVersion(), typeOf(r.getEcosystem()), r.getFile(),
                        r.getRiskLevel(), r.getRiskReason(), r.getLatestVersion(),
                        Boolean.TRUE.equals(r.getIsEol())))
                .toList();
        return new DependencyResp(true, items);
    }

    private static String ecosystemOf(String type) {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "MAVEN" -> "maven";
            case "NPM" -> "npm";
            case "PIP" -> "pip";
            case "GO" -> "go";
            default -> "other";
        };
    }

    private static String typeOf(String ecosystem) {
        return switch (ecosystem == null ? "" : ecosystem) {
            case "maven" -> "MAVEN";
            case "npm" -> "NPM";
            case "pip" -> "PIP";
            case "go" -> "GO";
            default -> "OTHER";
        };
    }
}
