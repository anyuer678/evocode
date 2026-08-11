package com.evocode.service.analysis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.dto.scan.ScanResultResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.Project;
import com.evocode.enums.AnalysisStatus;
import com.evocode.enums.ProjectStatus;
import com.evocode.enums.Stage;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.scan.FileNodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * 创建项目后的异步快扫（FR-1.3：3 分钟内出档案）。
 * 创建 analysis(RUNNING,SCAN) → 调 /analyze/scan → 落 file_node + 更新档案 → SUCCEEDED(SCAN_DONE)。
 */
@Slf4j
@Service
public class QuickScanService {

    private final AnalysisMapper analysisMapper;
    private final ProjectMapper projectMapper;
    private final AnalyzerClient analyzerClient;
    private final FileNodeService fileNodeService;

    public QuickScanService(AnalysisMapper analysisMapper, ProjectMapper projectMapper,
                            AnalyzerClient analyzerClient, FileNodeService fileNodeService) {
        this.analysisMapper = analysisMapper;
        this.projectMapper = projectMapper;
        this.analyzerClient = analyzerClient;
        this.fileNodeService = fileNodeService;
    }

    @Async("quickScanExecutor")
    public void quickScan(Project project) {
        Long running = analysisMapper.selectCount(new QueryWrapper<Analysis>()
                .eq("project_id", project.getId())
                .eq("type", "FULL")
                .eq("status", AnalysisStatus.RUNNING.name()));
        if (running != null && running > 0) {
            log.info("快扫已在进行，跳过重复触发 projectId={}", project.getId());
            return;
        }

        Analysis analysis = new Analysis();
        analysis.setProjectId(project.getId());
        analysis.setType("FULL");
        analysis.setStatus(AnalysisStatus.RUNNING.name());
        analysis.setProgress(5);
        analysis.setStage(Stage.SCAN.name());
        analysis.setStartedAt(OffsetDateTime.now());
        analysisMapper.insert(analysis);

        project.setStatus(ProjectStatus.ANALYZING.name());
        projectMapper.updateById(project);

        try {
            // 传绝对路径：analyzer 与 backend 可能不在同一 cwd
            String codeDir = Path.of(project.getStoragePath()).toAbsolutePath().toString();
            ScanResultResp scan = analyzerClient.scan(project.getId(), codeDir);
            Analysis current = analysisMapper.selectById(analysis.getId());
            if (current != null && AnalysisStatus.CANCELLED.name().equals(current.getStatus())) {
                log.info("快扫被取消，projectId={}", project.getId());
                return;
            }
            fileNodeService.replaceSnapshot(project.getId(), analysis.getId(), scan.files());
            updateArchive(project, scan);
            analysis.setStatus(AnalysisStatus.SUCCEEDED.name());
            analysis.setStage(Stage.SCAN_DONE.name());
            analysis.setProgress(70);
            analysis.setFinishedAt(OffsetDateTime.now());
            analysisMapper.updateById(analysis);
        } catch (Exception e) {
            log.error("快扫失败 projectId={}", project.getId(), e);
            analysis.setStatus(AnalysisStatus.FAILED.name());
            analysis.setErrorMessage(e.getMessage());
            analysis.setFinishedAt(OffsetDateTime.now());
            analysisMapper.updateById(analysis);
            project.setStatus(ProjectStatus.FAILED.name());
            projectMapper.updateById(project);
        }
    }

    private void updateArchive(Project project, ScanResultResp scan) {
        project.setLangStats(scan.languages());
        project.setFrameworkTags(scan.frameworks());
        project.setLocTotal(scan.locTotal());
        project.setFileCount(scan.fileCount());
        project.setIgnoredCount(scan.ignoredCount());
        project.setStatus(ProjectStatus.READY.name());
        projectMapper.updateById(project);
    }
}
