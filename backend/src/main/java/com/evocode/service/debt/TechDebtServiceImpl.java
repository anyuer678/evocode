package com.evocode.service.debt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.debt.TechDebtResp;
import com.evocode.entity.ArchViolation;
import com.evocode.entity.Hotspot;
import com.evocode.entity.Project;
import com.evocode.entity.QualityIssue;
import com.evocode.entity.TechDebt;
import com.evocode.mapper.ArchViolationMapper;
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
import java.util.regex.Pattern;

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

    private static final Pattern DEPEND_PATTERN =
            Pattern.compile("EOL|依赖|版本|升级", Pattern.CASE_INSENSITIVE);

    private final TechDebtMapper techDebtMapper;
    private final ProjectMapper projectMapper;
    private final ArchViolationMapper archViolationMapper;
    private final QualityIssueMapper qualityIssueMapper;
    private final HotspotMapper hotspotMapper;

    public TechDebtServiceImpl(TechDebtMapper techDebtMapper, ProjectMapper projectMapper,
                               ArchViolationMapper archViolationMapper,
                               QualityIssueMapper qualityIssueMapper,
                               HotspotMapper hotspotMapper) {
        this.techDebtMapper = techDebtMapper;
        this.projectMapper = projectMapper;
        this.archViolationMapper = archViolationMapper;
        this.qualityIssueMapper = qualityIssueMapper;
        this.hotspotMapper = hotspotMapper;
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
        // 项目级全量重建（与 knowledge_chunk 一致）：重新分析后旧分析产生的债
        // 全部替换，避免残留引用旧 analysis 的债（端到端实测发现）。
        // 注：MANUAL/AI_DOCTOR 源（无 ref_analysis_id）将来引入时需保留。
        techDebtMapper.delete(new LambdaQueryWrapper<TechDebt>()
                .eq(TechDebt::getProjectId, projectId));
        List<TechDebt> debts = new java.util.ArrayList<>();
        debts.addAll(fromArchitecture(projectId, analysisId));
        debts.addAll(fromQuality(projectId, analysisId));
        debts.addAll(fromEvolution(projectId, analysisId));
        debts.addAll(fromReportDepend(projectId, analysisId, reportJson));
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

    @SuppressWarnings("unchecked")
    private List<TechDebt> fromReportDepend(Long projectId, Long analysisId,
                                            Map<String, Object> reportJson) {
        if (reportJson == null) {
            return List.of();
        }
        Object risksObj = reportJson.get("risks");
        if (!(risksObj instanceof List<?> risks)) {
            return List.of();
        }
        List<TechDebt> out = new java.util.ArrayList<>();
        for (Object item : risks) {
            if (!(item instanceof Map<?, ?> risk)) {
                continue;
            }
            String title = str(risk.get("title"));
            if (title == null || !DEPEND_PATTERN.matcher(title).find()) {
                continue;
            }
            TechDebt debt = base(projectId, "DEPEND", analysisId);
            debt.setTitle(clip(title, 60));
            debt.setLevel(normalizeLevel(str(risk.get("level"))));
            debt.setDescription(str(risk.get("detail")));
            debt.setSuggestion(str(risk.get("suggestion")));
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

    private static String normalizeLevel(String level) {
        if (level == null) {
            return "MEDIUM";
        }
        String l = level.toUpperCase(Locale.ROOT);
        return Set.of("HIGH", "MEDIUM", "LOW").contains(l) ? l : "MEDIUM";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
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
}
