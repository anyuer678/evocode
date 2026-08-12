package com.evocode.service.debt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.debt.TechDebtCreateReq;
import com.evocode.dto.debt.TechDebtResp;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.Dependency;
import com.evocode.entity.Hotspot;
import com.evocode.entity.Project;
import com.evocode.entity.QualityIssue;
import com.evocode.entity.TechDebt;
import com.evocode.mapper.ArchViolationMapper;
import com.evocode.mapper.DependencyMapper;
import com.evocode.mapper.HotspotMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.mapper.QualityIssueMapper;
import com.evocode.mapper.TechDebtMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 技术债（06 §3.12）。
 */
@Service
public class TechDebtServiceImpl implements TechDebtService {

    private static final Set<String> OPEN_STATES = Set.of("OPEN", "DOING");
    /** 状态机：OPEN→DOING/DONE/WONTFIX、DOING→DONE（06 §3.12） */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("DOING", "DONE", "WONTFIX"),
            "DOING", Set.of("DONE"));

    private final TechDebtMapper techDebtMapper;
    private final ProjectMapper projectMapper;
    private final ArchViolationMapper archViolationMapper;
    private final QualityIssueMapper qualityIssueMapper;
    private final HotspotMapper hotspotMapper;
    private final DependencyMapper dependencyMapper;

    public TechDebtServiceImpl(TechDebtMapper techDebtMapper, ProjectMapper projectMapper,
                               ArchViolationMapper archViolationMapper,
                               QualityIssueMapper qualityIssueMapper,
                               HotspotMapper hotspotMapper,
                               DependencyMapper dependencyMapper) {
        this.techDebtMapper = techDebtMapper;
        this.projectMapper = projectMapper;
        this.archViolationMapper = archViolationMapper;
        this.qualityIssueMapper = qualityIssueMapper;
        this.hotspotMapper = hotspotMapper;
        this.dependencyMapper = dependencyMapper;
    }

    @Override
    public IPage<TechDebtResp> list(Long projectId, String status, int page, int size) {
        requireProject(projectId);
        LambdaQueryWrapper<TechDebt> wrapper = new LambdaQueryWrapper<TechDebt>()
                .eq(TechDebt::getProjectId, projectId)
                .orderByDesc(TechDebt::getCreatedAt);
        if (status != null && !status.isBlank()) {
            wrapper.eq(TechDebt::getStatus, status.toUpperCase(Locale.ROOT));
        }
        IPage<TechDebt> result = techDebtMapper.selectPage(new Page<>(page, size), wrapper);
        return result.convert(this::toResp);
    }

    @Override
    @Transactional
    public void updateStatus(Long id, String status, String resolveNote, String wonfixReason) {
        TechDebt debt = techDebtMapper.selectById(id);
        if (debt == null) {
            throw new BusinessException(ErrorCode.DEBT_NOT_FOUND, "技术债不存在");
        }
        String target = status == null ? "" : status.toUpperCase(Locale.ROOT);
        String current = debt.getStatus();
        Set<String> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new BusinessException(ErrorCode.DEBT_STATUS_INVALID,
                    String.format("状态迁移非法：%s→%s", current, target));
        }
        if ("DONE".equals(target) && (resolveNote == null || resolveNote.isBlank())) {
            throw new BusinessException(ErrorCode.DEBT_STATUS_INVALID, "DONE 必填 resolveNote");
        }
        if ("WONTFIX".equals(target) && (wonfixReason == null || wonfixReason.isBlank())) {
            throw new BusinessException(ErrorCode.DEBT_STATUS_INVALID, "WONTFIX 必填 wonfixReason");
        }
        debt.setStatus(target);
        debt.setResolveNote("DONE".equals(target) ? resolveNote : null);
        debt.setWonfixReason("WONTFIX".equals(target) ? wonfixReason : null);
        debt.setResolvedAt("DONE".equals(target) || "WONTFIX".equals(target)
                ? OffsetDateTime.now() : null);
        techDebtMapper.updateById(debt);
    }

    @Override
    @Transactional
    public void rebuildForAnalysis(Long projectId, Long analysisId,
                                   Map<String, Object> reportJson) {
        // 只删四聚合源（ARCH/QUALITY/EVOLUTION/DEPEND）：重新分析后旧分析产生的债
        // 全部替换，避免残留引用旧 analysis 的债（端到端实测发现）。
        // TD-04：MANUAL/AI_DOCTOR 源（无 ref_analysis_id）保留——手动登记/AI 确认
        // 的债是用户资产，不被重新分析清空。
        techDebtMapper.delete(new LambdaQueryWrapper<TechDebt>()
                .eq(TechDebt::getProjectId, projectId)
                .in(TechDebt::getSource, "ARCH", "QUALITY", "EVOLUTION", "DEPEND"));
        List<TechDebt> debts = new java.util.ArrayList<>();
        debts.addAll(fromArchitecture(projectId, analysisId));
        debts.addAll(fromQuality(projectId, analysisId));
        debts.addAll(fromEvolution(projectId, analysisId));
        debts.addAll(fromDepend(projectId, analysisId));
        for (TechDebt debt : debts) {
            techDebtMapper.insert(debt);
        }
    }

    // ---- 聚合源 ----

    private List<TechDebt> fromArchitecture(Long projectId, Long analysisId) {
        List<ArchViolation> violations = archViolationMapper.selectList(
                new LambdaQueryWrapper<ArchViolation>()
                        .eq(ArchViolation::getAnalysisId, analysisId)
                        .in(ArchViolation::getSeverity, "HIGH", "MEDIUM"));
        List<TechDebt> out = new java.util.ArrayList<>(violations.size());
        for (ArchViolation v : violations) {
            TechDebt debt = base(projectId, "ARCH", analysisId);
            debt.setTitle(clip(v.getViolationType() + "：" + clip(v.getDescription(), 60), 200));
            debt.setLevel(v.getSeverity());
            debt.setDescription(v.getDescription());
            debt.setSuggestion(v.getSuggestion());
            out.add(debt);
        }
        return out;
    }

    private List<TechDebt> fromQuality(Long projectId, Long analysisId) {
        List<QualityIssue> issues = qualityIssueMapper.selectList(
                new LambdaQueryWrapper<QualityIssue>()
                        .eq(QualityIssue::getAnalysisId, analysisId)
                        .in(QualityIssue::getSeverity, "BLOCKER", "CRITICAL"));
        List<TechDebt> out = new java.util.ArrayList<>(issues.size());
        for (QualityIssue q : issues) {
            TechDebt debt = base(projectId, "QUALITY", analysisId);
            debt.setTitle(clip(q.getMessage(), 60));
            debt.setLevel("BLOCKER".equals(q.getSeverity()) ? "HIGH" : "MEDIUM");
            debt.setDescription(q.getMessage());
            debt.setSuggestion(q.getAiSuggestion());
            out.add(debt);
        }
        return out;
    }

    private List<TechDebt> fromEvolution(Long projectId, Long analysisId) {
        List<Hotspot> hotspots = hotspotMapper.selectList(
                new LambdaQueryWrapper<Hotspot>()
                        .eq(Hotspot::getAnalysisId, analysisId)
                        .eq(Hotspot::getRiskLevel, "HIGH"));
        List<TechDebt> out = new java.util.ArrayList<>(hotspots.size());
        for (Hotspot h : hotspots) {
            TechDebt debt = base(projectId, "EVOLUTION", analysisId);
            debt.setTitle(h.getModule() + " 高风险演化热点");
            debt.setLevel("HIGH");
            debt.setDescription(String.join("；", h.getEvidence() == null
                    ? List.of() : h.getEvidence()));
            debt.setSuggestion(h.getAiConclusion());
            out.add(debt);
        }
        return out;
    }

    /** TD-04（P9d）：DEPEND 源改读 dependency 表（替代 P7 从 report_json.risks 提取的临时方案）。 */
    private List<TechDebt> fromDepend(Long projectId, Long analysisId) {
        List<Dependency> deps = dependencyMapper.selectList(
                new LambdaQueryWrapper<Dependency>()
                        .eq(Dependency::getAnalysisId, analysisId)
                        .in(Dependency::getRiskLevel, "HIGH", "MEDIUM"));
        List<TechDebt> out = new java.util.ArrayList<>(deps.size());
        for (Dependency d : deps) {
            TechDebt debt = base(projectId, "DEPEND", analysisId);
            debt.setTitle(clip(d.getName() + " 存在 EOL/版本风险", 60));
            debt.setLevel(d.getRiskLevel());
            debt.setDescription(d.getRiskReason());
            debt.setSuggestion(d.getLatestVersion() == null
                    ? d.getSuggestion() : "建议升级至 " + d.getLatestVersion());
            out.add(debt);
        }
        return out;
    }

    // ---- 工具 ----

    private TechDebt base(Long projectId, String source, Long analysisId) {
        TechDebt debt = new TechDebt();
        debt.setProjectId(projectId);
        debt.setSource(source);
        debt.setStatus("OPEN");
        debt.setRefAnalysisId(analysisId);
        return debt;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) {
            return "(无描述)";
        }
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private void requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在");
        }
    }

    private TechDebtResp toResp(TechDebt d) {
        return new TechDebtResp(d.getId(), d.getSource(), d.getTitle(), d.getLevel(),
                d.getDescription(), d.getSuggestion(), d.getStatus(), d.getRefAnalysisId(),
                d.getCreatedAt(), d.getResolvedAt());
    }

    // ---- TD-04：手动 / AI 医生登记 ----

    @Override
    @Transactional
    public TechDebtResp create(Long projectId, TechDebtCreateReq req) {
        requireProject(projectId);
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "title 必填");
        }
        if (req.title().length() > 200) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "title 过长（≤200）");
        }
        String source = req.source() == null ? "MANUAL" : req.source().toUpperCase(Locale.ROOT);
        if (!Set.of("MANUAL", "AI_DOCTOR").contains(source)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "source 仅支持 MANUAL/AI_DOCTOR");
        }
        TechDebt debt = new TechDebt()
                .setProjectId(projectId)
                .setSource(source)
                .setTitle(req.title().trim())
                .setLevel(normalizeLevel(req.level()))
                .setDescription(req.description())
                .setSuggestion(req.suggestion())
                .setStatus("OPEN")
                .setRefAnalysisId(null); // 手动/AI 登记不绑定具体分析
        techDebtMapper.insert(debt);
        return toResp(debt);
    }

    private static String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "MEDIUM";
        }
        String l = level.toUpperCase(Locale.ROOT);
        return Set.of("HIGH", "MEDIUM", "LOW").contains(l) ? l : "MEDIUM";
    }
}
