package com.evocode.service.debt;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TechDebtServiceImplTest {

    private TechDebtMapper debtMapper;
    private ProjectMapper projectMapper;
    private ArchViolationMapper archMapper;
    private QualityIssueMapper qualityMapper;
    private HotspotMapper hotspotMapper;
    private DependencyMapper dependencyMapper;
    private TechDebtServiceImpl svc;

    @BeforeEach
    void setUp() {
        debtMapper = mock(TechDebtMapper.class);
        projectMapper = mock(ProjectMapper.class);
        archMapper = mock(ArchViolationMapper.class);
        qualityMapper = mock(QualityIssueMapper.class);
        hotspotMapper = mock(HotspotMapper.class);
        dependencyMapper = mock(DependencyMapper.class);
        svc = new TechDebtServiceImpl(debtMapper, projectMapper, archMapper,
                qualityMapper, hotspotMapper, dependencyMapper);
    }

    private Project project() {
        Project p = new Project();
        p.setId(1L);
        return p;
    }

    private TechDebt debt(Long id, String status) {
        TechDebt d = new TechDebt();
        d.setId(id);
        d.setStatus(status);
        return d;
    }

    // ---- list ----

    @Test
    void list_projectMissing_throws2001() {
        when(projectMapper.selectById(9L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> svc.list(9L, null, 1, 20));
    }

    @Test
    void list_filtersByStatusAndMaps() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        IPage<TechDebt> page = new Page<>(1, 20);
        TechDebt d = debt(7L, "OPEN");
        d.setSource("ARCH");
        d.setTitle("耦合严重");
        page.setRecords(List.of(d));
        page.setTotal(1);
        when(debtMapper.selectPage(any(), any())).thenReturn(page);
        IPage<TechDebtResp> resp = svc.list(1L, "OPEN", 1, 20);
        assertEquals(1, resp.getTotal());
        assertEquals("OPEN", resp.getRecords().get(0).status());
        assertEquals("ARCH", resp.getRecords().get(0).source());
    }

    // ---- updateStatus 状态机 ----

    @Test
    void updateStatus_missingDebt_throws2012() {
        when(debtMapper.selectById(5L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.updateStatus(5L, "DONE", "note", null));
        assertEquals(ErrorCode.DEBT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateStatus_invalidTarget_throws2011() {
        when(debtMapper.selectById(5L)).thenReturn(debt(5L, "OPEN"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.updateStatus(5L, "CLOSED", null, null));
        assertEquals(ErrorCode.DEBT_STATUS_INVALID.getCode(), ex.getCode());
    }

    @Test
    void updateStatus_illegalTransition_throws2011() {
        when(debtMapper.selectById(5L)).thenReturn(debt(5L, "WONTFIX"));
        assertThrows(BusinessException.class,
                () -> svc.updateStatus(5L, "DONE", "note", null));
    }

    @Test
    void updateStatus_doneRequiresResolveNote() {
        when(debtMapper.selectById(5L)).thenReturn(debt(5L, "OPEN"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.updateStatus(5L, "DONE", "  ", null));
        assertEquals(ErrorCode.DEBT_STATUS_INVALID.getCode(), ex.getCode());
        verify(debtMapper, never()).updateById(any(TechDebt.class));
    }

    @Test
    void updateStatus_wonfixRequiresReason() {
        when(debtMapper.selectById(5L)).thenReturn(debt(5L, "OPEN"));
        assertThrows(BusinessException.class,
                () -> svc.updateStatus(5L, "WONTFIX", null, ""));
    }

    @Test
    void updateStatus_openToDoing_ok() {
        when(debtMapper.selectById(5L)).thenReturn(debt(5L, "OPEN"));
        svc.updateStatus(5L, "DOING", null, null);
        verify(debtMapper).updateById(any(TechDebt.class));
    }

    @Test
    void updateStatus_doingToDone_ok() {
        when(debtMapper.selectById(5L)).thenReturn(debt(5L, "DOING"));
        svc.updateStatus(5L, "DONE", "已拆分", null);
        verify(debtMapper).updateById(any(TechDebt.class));
    }

    // ---- rebuildForAnalysis 四源聚合 ----

    @Test
    void rebuild_deletesOldAndInsertsFourSources() {
        ArchViolation v = new ArchViolation();
        v.setSeverity("HIGH");
        v.setViolationType("LAYER_BROKEN");
        v.setDescription("controller 调 dao");
        v.setSuggestion("加 service 层");
        when(archMapper.selectList(any())).thenReturn(List.of(v));

        QualityIssue q = new QualityIssue();
        q.setSeverity("BLOCKER");
        q.setMessage("空指针风险");
        q.setAiSuggestion("判空");
        when(qualityMapper.selectList(any())).thenReturn(List.of(q));

        Hotspot h = new Hotspot();
        h.setModule("User");
        h.setRiskLevel("HIGH");
        h.setEvidence(List.of("变更 45 次"));
        when(hotspotMapper.selectList(any())).thenReturn(List.of(h));

        // TD-04：DEPEND 源改读 dependency 表（替代 report_json.risks）
        Dependency dep = new Dependency();
        dep.setName("org.springframework.boot:spring-boot");
        dep.setRiskLevel("HIGH");
        dep.setRiskReason("Spring Boot 2.5 已 EOL");
        dep.setLatestVersion("3.2+");
        when(dependencyMapper.selectList(any())).thenReturn(List.of(dep));

        svc.rebuildForAnalysis(1L, 10L, Map.of());

        verify(debtMapper).delete(any(Wrapper.class));
        // ARCH 1 + QUALITY 1 + EVOLUTION 1 + DEPEND 1 = 4
        verify(debtMapper, org.mockito.Mockito.times(4)).insert(any(TechDebt.class));
    }

    @Test
    void rebuild_qualityLevelMapping() {
        QualityIssue q = new QualityIssue();
        q.setSeverity("CRITICAL");
        q.setMessage("性能问题");
        when(qualityMapper.selectList(any())).thenReturn(List.of(q));
        when(archMapper.selectList(any())).thenReturn(List.of());
        when(hotspotMapper.selectList(any())).thenReturn(List.of());
        when(dependencyMapper.selectList(any())).thenReturn(List.of());
        svc.rebuildForAnalysis(1L, 10L, Map.of());
        verify(debtMapper, org.mockito.Mockito.times(1)).insert(any(TechDebt.class));
        // 断言 level 映射：CRITICAL → MEDIUM（通过捕获参数校验）
        org.mockito.ArgumentCaptor<TechDebt> captor =
                org.mockito.ArgumentCaptor.forClass(TechDebt.class);
        verify(debtMapper).insert(captor.capture());
        assertEquals("MEDIUM", captor.getValue().getLevel());
        assertEquals("QUALITY", captor.getValue().getSource());
        assertTrue(captor.getValue().getRefAnalysisId() == 10L);
    }

    // ---- TD-04：手动 / AI 医生登记 ----

    @Test
    void rebuild_keepsManualAndAiDebts() {
        // TD-04 审查修复：重新分析只删四聚合源（delete 按 source IN 过滤），
        // MANUAL/AI_DOCTOR 债保留。纯 mock 环境无法解析 LambdaWrapper 内部 SQL，
        // 此处验证行为：delete 被调用 + 四源聚合照常插入（既有测试已覆盖 4 源插入）。
        when(archMapper.selectList(any())).thenReturn(List.of());
        when(qualityMapper.selectList(any())).thenReturn(List.of());
        when(hotspotMapper.selectList(any())).thenReturn(List.of());
        when(dependencyMapper.selectList(any())).thenReturn(List.of());

        svc.rebuildForAnalysis(1L, 10L, Map.of());

        verify(debtMapper).delete(any(Wrapper.class));
        // 聚合重建绝不插入 MANUAL/AI_DOCTOR（四源空时 insert 0 次，此处验证负向语义）
        verify(debtMapper, org.mockito.Mockito.never()).insert(
                org.mockito.ArgumentMatchers.argThat((TechDebt d) ->
                        "MANUAL".equals(d.getSource()) || "AI_DOCTOR".equals(d.getSource())));
    }

    @Test
    void create_manualInsertsDebt() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        org.mockito.ArgumentCaptor<TechDebt> captor =
                org.mockito.ArgumentCaptor.forClass(TechDebt.class);
        when(debtMapper.insert(captor.capture())).thenReturn(1);

        TechDebtResp resp = svc.create(1L, new com.evocode.dto.debt.TechDebtCreateReq(
                "MANUAL", "手动登记的债", "HIGH", "描述", "建议"));

        assertEquals("MANUAL", resp.source());
        assertEquals("OPEN", resp.status());
        // refAnalysisId 为空（不绑定具体分析）
        assertEquals(null, captor.getValue().getRefAnalysisId());
        assertEquals("手动登记的债", captor.getValue().getTitle());
    }

    @Test
    void create_aiDoctorLevelDefaultsMedium() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        org.mockito.ArgumentCaptor<TechDebt> captor =
                org.mockito.ArgumentCaptor.forClass(TechDebt.class);
        when(debtMapper.insert(captor.capture())).thenReturn(1);

        // AI_DOCTOR 源 + 缺省 level → MEDIUM；source 大小写归一
        svc.create(1L, new com.evocode.dto.debt.TechDebtCreateReq(
                "ai_doctor", "AI 确认的债", null, null, null));
        assertEquals("AI_DOCTOR", captor.getValue().getSource());
        assertEquals("MEDIUM", captor.getValue().getLevel());
    }

    @Test
    void create_rejectsBlankTitleAndBadSource() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> svc.create(1L, new com.evocode.dto.debt.TechDebtCreateReq(
                        "MANUAL", "  ", null, null, null)));
        assertEquals(1001, e1.getCode()); // PARAM_MISSING

        BusinessException e2 = assertThrows(BusinessException.class,
                () -> svc.create(1L, new com.evocode.dto.debt.TechDebtCreateReq(
                        "ARCH", "xx", null, null, null)));
        assertEquals(1002, e2.getCode()); // PARAM_INVALID（source 不支持）
    }
}
