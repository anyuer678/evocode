package com.evocode.common.util;

import java.nio.file.Path;
import java.util.Set;

/**
 * 路径安全校验（AD-11：路径三重校验；06 §3.8 安全实现依赖）。
 * 规则：拒绝相对穿越（..）、绝对路径、驱动器、NUL；隐藏文件仅白名单（FR-2.2）。
 */
public final class PathSafetyUtil {

    /** 隐藏文件白名单（FR-2.2：隐藏文件仅保留 .github 等） */
    private static final Set<String> HIDDEN_WHITELIST = Set.of(
            ".github", ".gitignore", ".dockerignore", ".gitattributes", ".editorconfig",
            ".npmrc", ".env.example", ".evocodeignore",
            ".prettierrc", ".prettierrc.json", ".prettierrc.cjs",
            ".eslintrc", ".eslintrc.json", ".eslintrc.cjs",
            ".flake8", ".pylintrc", ".babelrc", ".browserslistrc", ".nvmrc");

    private PathSafetyUtil() {
    }

    /** 统一分隔符为 /，并去掉结尾斜杠。 */
    public static String normalize(String path) {
        if (path == null) {
            return null;
        }
        String p = path.replace('\\', '/').trim();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * zip 条目名是否安全：非空、相对、无 .. 段、无绝对路径/驱动器/NUL。
     * 注意：目录条目应去掉末尾 '/' 后再校验。
     */
    public static boolean isSafeEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return false;
        }
        String p = normalize(entryName);
        if (p.isEmpty() || p.indexOf('\u0000') >= 0) {
            return false;
        }
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.startsWith("//")) {
            return false;
        }
        for (String seg : p.split("/")) {
            if (seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** 隐藏文件/目录（以 . 开头）是否在白名单内。 */
    public static boolean isAllowedHiddenFile(String name) {
        String n = normalize(name);
        if (n == null || n.isEmpty()) {
            return false;
        }
        int idx = n.indexOf('/');
        String first = idx < 0 ? n : n.substring(0, idx);
        if (first.isEmpty() || !first.startsWith(".")) {
            return true;
        }
        return HIDDEN_WHITELIST.contains(first);
    }

    /**
     * 将相对路径解析到 root 内；任何越界（.. / 绝对路径）抛 IllegalArgumentException。
     */
    public static Path resolveInside(Path root, String relativePath) {
        if (!isSafeEntryName(relativePath)) {
            throw new IllegalArgumentException("非法路径: " + relativePath);
        }
        String norm = normalize(relativePath);
        Path resolved = root.resolve(norm).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("路径越界: " + relativePath);
        }
        return resolved;
    }
}
