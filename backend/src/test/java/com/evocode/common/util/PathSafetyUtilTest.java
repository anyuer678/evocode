package com.evocode.common.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-U-22：路径白名单逻辑正确（含符号链接与隐藏文件）。
 */
class PathSafetyUtilTest {

    @Test
    void safeRelativePathAccepted() {
        assertTrue(PathSafetyUtil.isSafeEntryName("src/main/java/UserService.java"));
        assertTrue(PathSafetyUtil.isSafeEntryName("pom.xml"));
        assertTrue(PathSafetyUtil.isSafeEntryName("a/b/c/d.txt"));
    }

    @Test
    void traversalRejected() {
        assertFalse(PathSafetyUtil.isSafeEntryName("../evil"));
        assertFalse(PathSafetyUtil.isSafeEntryName("a/../../evil"));
        assertFalse(PathSafetyUtil.isSafeEntryName(".."));
        assertFalse(PathSafetyUtil.isSafeEntryName("a/../b"));
    }

    @Test
    void absolutePathRejected() {
        assertFalse(PathSafetyUtil.isSafeEntryName("/etc/passwd"));
        assertFalse(PathSafetyUtil.isSafeEntryName("C:\\evil.txt"));
        assertFalse(PathSafetyUtil.isSafeEntryName("\\\\server\\share"));
    }

    @Test
    void backslashNormalizedToSlash() {
        assertTrue(PathSafetyUtil.isSafeEntryName("src\\main\\App.java"));
        assertEquals("src/main/App.java", PathSafetyUtil.normalize("src\\main\\App.java"));
    }

    @Test
    void hiddenFilesWhitelistOnly() {
        assertTrue(PathSafetyUtil.isAllowedHiddenFile(".github"));
        assertTrue(PathSafetyUtil.isAllowedHiddenFile(".evocodeignore"));
        assertTrue(PathSafetyUtil.isAllowedHiddenFile(".gitignore"));
        assertFalse(PathSafetyUtil.isAllowedHiddenFile(".ssh"));
        assertFalse(PathSafetyUtil.isAllowedHiddenFile(".env"));
        assertFalse(PathSafetyUtil.isAllowedHiddenFile(".git"));
    }

    @Test
    void resolveInsideRootBlocksEscape() {
        Path root = Path.of("data/projects/1");
        assertEquals(Path.of("data/projects/1/src/A.java"),
                PathSafetyUtil.resolveInside(root, "src/A.java"));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafetyUtil.resolveInside(root, "../other/x"));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafetyUtil.resolveInside(root, "/etc/passwd"));
    }

    @Test
    void emptyAndNulRejected() {
        assertFalse(PathSafetyUtil.isSafeEntryName(""));
        assertFalse(PathSafetyUtil.isSafeEntryName("a\u0000b"));
        assertFalse(PathSafetyUtil.isSafeEntryName(null));
    }
}
