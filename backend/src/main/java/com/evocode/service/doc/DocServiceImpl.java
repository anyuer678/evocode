package com.evocode.service.doc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.dto.doc.DocResp;
import com.evocode.dto.scan.ScanFileResp;
import com.evocode.entity.Analysis;
import com.evocode.entity.GeneratedDoc;
import com.evocode.entity.Project;
import com.evocode.mapper.AnalysisMapper;
import com.evocode.mapper.GeneratedDocMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.ArchitectureService;
import com.evocode.service.analysis.AnalyzerClient;
import com.evocode.service.scan.FileNodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生成文档（06 §3.14）。generate 同步调 analyzer（AD-P7-2），
 * 失败 → 3001（LLM_NO_KEY/LLM_FAILED 统一映射，文档无法规则降级）。
 */
@Service
public class DocServiceImpl implements DocService {

    private static final Set<String> DOC_TYPES = Set.of("README", "ARCH", "API");

    private final GeneratedDocMapper docMapper;
    private final ProjectMapper projectMapper;
    private final AnalysisMapper analysisMapper;
    private final FileNodeService fileNodeService;
    private final ArchitectureService architectureService;
    private final AnalyzerClient analyzerClient;
    private final ObjectMapper objectMapper;

    public DocServiceImpl(GeneratedDocMapper docMapper, ProjectMapper projectMapper,
                          AnalysisMapper analysisMapper, FileNodeService fileNodeService,
                          ArchitectureService architectureService,
                          AnalyzerClient analyzerClient, ObjectMapper objectMapper) {
        this.docMapper = docMapper;
        this.projectMapper = projectMapper;
        this.analysisMapper = analysisMapper;
        this.fileNodeService = fileNodeService;
        this.architectureService = architectureService;
        this.analyzerClient = analyzerClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DocResp> list(Long projectId) {
        requireProject(projectId);
        return docMapper.selectList(new LambdaQueryWrapper<GeneratedDoc>()
                        .eq(GeneratedDoc::getProjectId, projectId))
                .stream().map(this::toResp).toList();
    }

    @Override
    @Transactional
    public DocResp generate(Long projectId, String docType) {
        Project project = requireProject(projectId);
        String type = docType == null ? "" : docType.toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "docType 必须是 README/ARCH/API");
        }
        Map<String, Object> scan = "API".equals(type) ? null : buildScan(project);
        Map<String, Object> arch = "ARCH".equals(type) ? loadArch(project) : null;
        String codeDir = "API".equals(type) ? project.getStoragePath() : null;
        Map<String, Object> projectInfo = Map.of(
                "name", project.getName() == null ? "" : project.getName(),
                "description", project.getDescription() == null ? "" : project.getDescription());
        AnalyzerClient.DocResp generated =
                analyzerClient.doc(projectId, type, scan, arch, projectInfo, codeDir);
        GeneratedDoc doc = docMapper.selectOne(new LambdaQueryWrapper<GeneratedDoc>()
                .eq(GeneratedDoc::getProjectId, projectId)
                .eq(GeneratedDoc::getDocType, type));
        if (doc == null) {
            doc = new GeneratedDoc();
            doc.setProjectId(projectId);
            doc.setDocType(type);
            doc.setTitle(generated.title());
            doc.setContent(generated.content());
            doc.setVersion(1);
            doc.setEdited(false);
            docMapper.insert(doc);
        } else {
            // AD-P7-3：重新生成覆盖（edited 保护由前端二次确认）
            doc.setTitle(generated.title());
            doc.setContent(generated.content());
            doc.setVersion(doc.getVersion() + 1);
            docMapper.updateById(doc);
        }
        return toResp(doc);
    }

    @Override
    @Transactional
    public DocResp edit(Long docId, String content) {
        GeneratedDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND, "文档不存在");
        }
        if (content == null) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "content 必填");
        }
        doc.setContent(content);
        doc.setEdited(true);
        doc.setVersion(doc.getVersion() + 1);
        docMapper.updateById(doc);
        return toResp(doc);
    }

    // ---- 组装 analyzer 输入 ----

    private Map<String, Object> buildScan(Project project) {
        List<ScanFileResp> files = fileNodeService.listAllForReport(project.getId());
        List<Map<String, Object>> fileList = new java.util.ArrayList<>(files.size());
        for (ScanFileResp f : files) {
            Map<String, Object> m = new HashMap<>();
            m.put("path", f.path() == null ? "" : f.path());
            m.put("language", f.language() == null ? "" : f.language());
            m.put("loc", f.loc() == null ? 0 : f.loc());
            m.put("sizeBytes", f.sizeBytes() == null ? 0 : f.sizeBytes());
            fileList.add(m);
        }
        Map<String, Object> scan = new HashMap<>();
        scan.put("languages", project.getLangStats());
        scan.put("locTotal", project.getLocTotal());
        scan.put("fileCount", project.getFileCount());
        scan.put("frameworks", project.getFrameworkTags());
        scan.put("files", fileList);
        return scan;
    }

    private Map<String, Object> loadArch(Project project) {
        Analysis latest = analysisMapper.selectOne(new LambdaQueryWrapper<Analysis>()
                .eq(Analysis::getProjectId, project.getId())
                .orderByDesc(Analysis::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return Map.of("nodes", List.of(), "edges", List.of(), "violations", List.of());
        }
        return objectMapper.convertValue(
                architectureService.getForProject(project.getId(), latest.getId()), Map.class);
    }

    private Project requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private DocResp toResp(GeneratedDoc d) {
        return new DocResp(d.getId(), d.getDocType(), d.getTitle(), d.getContent(),
                d.getVersion(), d.getEdited(), d.getCreatedAt());
    }
}
