package com.evocode.service.doc;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocServiceImplTest {

    private GeneratedDocMapper docMapper;
    private ProjectMapper projectMapper;
    private AnalysisMapper analysisMapper;
    private FileNodeService fileNodeService;
    private ArchitectureService architectureService;
    private AnalyzerClient analyzerClient;
    private DocServiceImpl svc;

    @BeforeEach
    void setUp() {
        docMapper = mock(GeneratedDocMapper.class);
        projectMapper = mock(ProjectMapper.class);
        analysisMapper = mock(AnalysisMapper.class);
        fileNodeService = mock(FileNodeService.class);
        architectureService = mock(ArchitectureService.class);
        analyzerClient = mock(AnalyzerClient.class);
        svc = new DocServiceImpl(docMapper, projectMapper, analysisMapper,
                fileNodeService, architectureService, analyzerClient, new ObjectMapper());
    }

    private Project project() {
        Project p = new Project();
        p.setId(1L);
        p.setName("demo");
        p.setStoragePath("data/projects/1");
        return p;
    }

    @Test
    void list_projectMissing_throws2001() {
        when(projectMapper.selectById(9L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> svc.list(9L, null));
    }

    @Test
    void list_returnsDocs() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        GeneratedDoc d = new GeneratedDoc();
        d.setId(5L);
        d.setDocType("README");
        d.setTitle("T");
        d.setVersion(2);
        when(docMapper.selectList(any())).thenReturn(List.of(d));
        List<DocResp> resp = svc.list(1L, null);
        assertEquals(1, resp.size());
        assertEquals("README", resp.get(0).docType());
    }

    @Test
    void generate_invalidType_throws1002() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.generate(1L, "PDF", false));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void generate_analyzerDown_throws3001() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(analyzerClient.doc(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.ANALYZER_UNREACHABLE, "down"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.generate(1L, "README", false));
        assertEquals(ErrorCode.ANALYZER_UNREACHABLE.getCode(), ex.getCode());
    }

    @Test
    void generate_newDoc_upsertsVersion1() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        when(fileNodeService.listAllForReport(1L)).thenReturn(List.of(
                new ScanFileResp("src/A.java", "Java", 10, 100)));
        when(docMapper.selectOne(any())).thenReturn(null);
        when(analyzerClient.doc(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AnalyzerClient.DocResp("README", "Demo 说明", "# Demo"));
        when(docMapper.insert(any(GeneratedDoc.class))).thenAnswer(inv -> {
            GeneratedDoc d = inv.getArgument(0);
            d.setId(7L);
            return 1;
        });
        DocResp resp = svc.generate(1L, "README", false);
        assertEquals("README", resp.docType());
        assertEquals(1, resp.version());
        verify(docMapper).insert(any(GeneratedDoc.class));
    }

    @Test
    void generate_editedDocWithoutForce_throws2014() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        GeneratedDoc existing = new GeneratedDoc();
        existing.setId(7L);
        existing.setVersion(1);
        existing.setEdited(true);
        when(docMapper.selectOne(any())).thenReturn(existing);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.generate(1L, "README", false));
        assertEquals(ErrorCode.DOC_EDITED.getCode(), ex.getCode());
        verify(docMapper, never()).updateById(any(GeneratedDoc.class));
    }

    @Test
    void generate_existingDoc_bumpsVersion() {
        when(projectMapper.selectById(1L)).thenReturn(project());
        GeneratedDoc existing = new GeneratedDoc();
        existing.setId(7L);
        existing.setVersion(1);
        when(docMapper.selectOne(any())).thenReturn(existing);
        when(analyzerClient.doc(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AnalyzerClient.DocResp("README", "新标题", "新内容"));
        DocResp resp = svc.generate(1L, "README", false);
        assertEquals(2, resp.version());
        verify(docMapper).updateById(any(GeneratedDoc.class));
        verify(docMapper, never()).insert(any(GeneratedDoc.class));
    }

    @Test
    void edit_missing_throws2013() {
        when(docMapper.selectById(5L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.edit(5L, "x"));
        assertEquals(ErrorCode.DOC_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void edit_bumpsVersionAndMarksEdited() {
        GeneratedDoc d = new GeneratedDoc();
        d.setId(5L);
        d.setVersion(1);
        when(docMapper.selectById(5L)).thenReturn(d);
        DocResp resp = svc.edit(5L, "人工内容");
        assertEquals(2, resp.version());
        assertTrue(resp.edited());
        verify(docMapper).updateById(any(GeneratedDoc.class));
    }
}
