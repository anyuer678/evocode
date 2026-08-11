package com.evocode.service.project;

import com.evocode.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-U-05 / T-U-06 / T-U-07：zip 路径穿越、超限、单层目录上移。
 */
class UploadServiceTest {

    @TempDir
    Path tempDir;

    private final UploadService service = new UploadServiceImpl(1024 * 1024, 50);

    private static byte[] zipBytes(String... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String name : entries) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(("content of " + name).getBytes());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static MockMultipartFile zipFile(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/zip", bytes);
    }

    @Test
    void traversalEntryRejectedAndTempCleaned() throws Exception {
        byte[] bytes = zipBytes("a.txt", "../evil.txt");
        assertThrows(BusinessException.class,
                () -> service.extractZip(tempDir, zipFile("evil.zip", bytes)));
        assertTrue(isDirEmpty(tempDir), "临时目录必须清理");
    }

    @Test
    void absoluteEntryRejected() throws Exception {
        byte[] bytes = zipBytes("/etc/passwd");
        assertThrows(BusinessException.class,
                () -> service.extractZip(tempDir, zipFile("evil.zip", bytes)));
        assertTrue(isDirEmpty(tempDir));
    }

    @Test
    void hiddenFileOutsideWhitelistRejected() throws Exception {
        byte[] bytes = zipBytes("src/App.java", ".ssh/id_rsa");
        assertThrows(BusinessException.class,
                () -> service.extractZip(tempDir, zipFile("evil.zip", bytes)));
    }

    @Test
    void wrongExtensionRejected() throws Exception {
        byte[] bytes = zipBytes("a.txt");
        assertThrows(BusinessException.class,
                () -> service.extractZip(tempDir, zipFile("evil.jar", bytes)));
    }

    @Test
    void extractedSizeOverLimitRejected() throws Exception {
        UploadService tiny = new UploadServiceImpl(10, 50);
        byte[] bytes = zipBytes("big.txt");
        assertThrows(BusinessException.class,
                () -> tiny.extractZip(tempDir, zipFile("big.zip", bytes)));
    }

    @Test
    void fileCountOverLimitRejected() throws Exception {
        UploadService small = new UploadServiceImpl(1024 * 1024, 5);
        byte[] bytes = zipBytes("f1", "f2", "f3", "f4", "f5", "f6");
        assertThrows(BusinessException.class,
                () -> small.extractZip(tempDir, zipFile("many.zip", bytes)));
    }

    @Test
    void singleTopLevelDirPromotedToRoot() throws Exception {
        byte[] bytes = zipBytes("proj/README.md", "proj/src/main.py");
        Path root = service.extractZip(tempDir, zipFile("proj.zip", bytes));
        assertEquals(tempDir.resolve("proj").normalize(), root.normalize(), "单层目录应上移作为项目根");
        assertTrue(Files.exists(root.resolve("src/main.py")));
    }

    @Test
    void flatZipUsesTempDirAsRoot() throws Exception {
        byte[] bytes = zipBytes("README.md", "src/main.py");
        Path root = service.extractZip(tempDir, zipFile("flat.zip", bytes));
        assertEquals(tempDir.normalize(), root.normalize());
    }

    private boolean isDirEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        }
    }
}
