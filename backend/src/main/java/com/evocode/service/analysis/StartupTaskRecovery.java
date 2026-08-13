package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.common.ErrorCode;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 启动恢复（AD-019）：扫描崩溃残留的 PENDING/RUNNING 分析任务 → 标记 FAILED，
 * 受影响项目 ANALYZING → READY，避免任务无声卡死（压测证实重启后残留任务无恢复机制）。
 *
 * <p>单实例假设：启动时存在的 PENDING/RUNNING 必为上次进程崩溃残留（正常终止不会残留）。
 * 仅标记失败不自动重跑（用户重新触发分析）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupTaskRecovery implements ApplicationRunner {

    private final AnalysisMapper analysisMapper;
    private final ProjectMapper projectMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<Analysis> stuck = analysisMapper.selectList(new QueryWrapper<Analysis>()
                .in("status", AnalysisStatus.PENDING.name(), AnalysisStatus.RUNNING.name()));
        if (stuck.isEmpty()) {
            log.info("启动恢复：无中断的分析任务");
            return;
        }
        int recovered = 0;
        for (Analysis a : stuck) {
            a.setStatus(AnalysisStatus.FAILED.name());
            // 审查：落 TASK_INTERRUPTED(5003) 到 error_code（此前 5003 定义了未引用，契约漂移）
            a.setErrorCode(String.valueOf(ErrorCode.TASK_INTERRUPTED.getCode()));
            a.setErrorMessage("服务重启导致任务中断，请重新发起分析");
            a.setFinishedAt(OffsetDateTime.now());
            analysisMapper.updateById(a);
            // 项目崩溃时可能残留 ANALYZING → 恢复 READY
            Project p = projectMapper.selectById(a.getProjectId());
            if (p != null && ProjectStatus.ANALYZING.name().equals(p.getStatus())) {
                p.setStatus(ProjectStatus.READY.name());
                projectMapper.updateById(p);
            }
            recovered++;
            log.warn("启动恢复：中断分析标记失败 analysisId={} projectId={}",
                    a.getId(), a.getProjectId());
        }
        log.info("启动恢复完成：标记 {} 个中断分析为 FAILED", recovered);
    }
}
