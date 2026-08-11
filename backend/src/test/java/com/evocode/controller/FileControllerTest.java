package com.evocode.controller;

import com.evocode.common.BusinessException;
import com.evocode.config.EvocodeProperties;
import com.evocode.entity.FileNode;
import com.evocode.entity.Project;
import com.evocode.mapper.FileNodeMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.scan.FileNodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T-U-17 / T-U-18：content 越权与超限（400/2005）。
 */
class FileControllerTest {

    @TempDir
    Path root;

    private ProjectMapper projectMapper;
    private FileNodeMapper fileNodeMapper;
    private FileController controller;

    @BeforeEach
    void setUp() throws Exception {
        projectMapper = Mockito.mock(ProjectMapper.class);
        fileNodeMapper = Mockito.mock(FileNodeMapper.class);
        EvocodeProperties props = new EvocodeProperties();
        FileNodeService fileNodeService = new FileNodeService(fileNodeMapper);

        Project project = new Project();
        project.setId(1L);
        project.setStoragePath(root.toString());
        when(projectMapper.selectById(1L)).thenReturn(project);

        controller = new FileController(projectMapper, fileNodeMapper, fileNodeService, props);
    }

    @Test
    void traversalPathNotInWhitelistRejected() {
        when(fileNodeMapper.selectOne(any())).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.content(1L, "../etc/passwd"));
        assertEquals(2005, e.getCode());
    }

    @Test
    void whitelistPathResolvingOutsideRootRejected() throws Exception {
        FileNode node = new FileNode();
        node.setPath("../escape.txt");
        node.setLanguage("OTHER");
        when(fileNodeMapper.selectOne(any())).thenReturn(node);
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.content(1L, "../escape.txt"));
        assertEquals(2005, e.getCode());
    }

    @Test
    void fileOverTwoMbRejected() throws Exception {
        FileNode node = new FileNode();
        node.setPath("src/Big.java");
        node.setLanguage("Java");
        when(fileNodeMapper.selectOne(any())).thenReturn(node);
        Path file = root.resolve("src").resolve("Big.java");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[2 * 1024 * 1024 + 1]);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.content(1L, "src/Big.java"));
        assertEquals(2005, e.getCode());
    }

    @Test
    void binaryFileRejected() throws Exception {
        FileNode node = new FileNode();
        node.setPath("src/a.bin");
        node.setLanguage("OTHER");
        when(fileNodeMapper.selectOne(any())).thenReturn(node);
        Path file = root.resolve("src").resolve("a.bin");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{0x01, 0x00, 0x02});

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.content(1L, "src/a.bin"));
        assertEquals(2005, e.getCode());
    }

    @Test
    void validTextFileReturned() throws Exception {
        FileNode node = new FileNode();
        node.setPath("src/A.java");
        node.setLanguage("Java");
        node.setLoc(3);
        when(fileNodeMapper.selectOne(any())).thenReturn(node);
        Path file = root.resolve("src").resolve("A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "public class A {}\n");

        var resp = controller.content(1L, "src/A.java").getData();
        assertEquals("src/A.java", resp.path());
        assertEquals("Java", resp.language());
        assertEquals(3, resp.loc());
        assertEquals("public class A {}\n", resp.content());
    }
}
