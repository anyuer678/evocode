package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.util.PathSafetyUtil;
import com.evocode.config.EvocodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * zip 上传实现（06 §3.1 校验顺序：① 扩展名 → ② 大小 → ③ 逐文件路径校验 → ④ 体积/文件数校验 → 原子移入由 ProjectService 执行）。
 */
@Slf4j
@Service
public class UploadServiceImpl implements UploadService {

    private final long maxExtractBytes;
    private final int maxFileCount;

    @Autowired
    public UploadServiceImpl(EvocodeProperties props) {
        this(props.getUploadMaxExtractBytes(), props.getUploadMaxFileCount());
    }

    /** 可注入上限，便于单测（T-U-06）。 */
    UploadServiceImpl(long maxExtractBytes, int maxFileCount) {
        this.maxExtractBytes = maxExtractBytes;
        this.maxFileCount = maxFileCount;
    }

    @Override
    public Path extractZip(Path tempDir, MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".zip")) {
            throw new BusinessException(ErrorCode.FILE_ILLEGAL, "仅支持 .zip 文件");
        }
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_ILLEGAL, "zip 文件为空");
        }
        try {
            extractEntries(tempDir, file.getInputStream());
            return promoteSingleTopDir(tempDir);
        } catch (BusinessException e) {
            cleanup(tempDir);
            throw e;
        } catch (IOException e) {
            cleanup(tempDir);
            throw new BusinessException(ErrorCode.FILE_ILLEGAL, "zip 解压失败：" + e.getMessage());
        }
    }

    private void extractEntries(Path tempDir, InputStream in) throws IOException {
        long totalBytes = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                // Windows Compress-Archive 生成的目录条目以 '\' 结尾，ZipInputStream.isDirectory()
                // 只认 '/'——按结尾分隔符补判，否则目录会被当文件复制而报错（端到端实测发现）
                if (entry.isDirectory() || name.endsWith("/") || name.endsWith("\\")) {
                    continue;
                }
                validateEntry(entry);
                Path target = PathSafetyUtil.resolveInside(tempDir, name);
                Files.createDirectories(target.getParent());
                long copied = Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                totalBytes += copied;
                count++;
                if (totalBytes > maxExtractBytes) {
                    throw new BusinessException(ErrorCode.FILE_ILLEGAL,
                            "解压后超过体积上限 " + (maxExtractBytes / 1024 / 1024) + "MB");
                }
                if (count > maxFileCount) {
                    throw new BusinessException(ErrorCode.FILE_ILLEGAL, "文件数超过上限 " + maxFileCount);
                }
            }
        }
    }

    private void validateEntry(ZipEntry entry) {
        String name = entry.getName();
        if (!PathSafetyUtil.isSafeEntryName(name) || !PathSafetyUtil.isAllowedHiddenFile(name)) {
            throw new BusinessException(ErrorCode.FILE_ILLEGAL, "zip 内存在非法路径（" + name + "）");
        }
        // 符号链接防护说明：本实现以 Files.copy 物化为普通文件、目标路径 resolveInside 限根内，
        // 不会创建链接，故无链接逃逸面（JDK ZipEntry 无公开 symlink 标志，commons-compress 才有）。
    }

    /** 单层顶层目录自动上移：若临时目录仅含一个子目录，则返回该子目录作为项目根（FR-1.1）。 */
    private Path promoteSingleTopDir(Path tempDir) throws IOException {
        try (Stream<Path> stream = Files.list(tempDir)) {
            List<Path> children = stream.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                return children.get(0);
            }
        }
        return tempDir;
    }

    private void cleanup(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .filter(p -> !p.equals(tempDir))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("临时目录清理失败: {}", tempDir, e);
        }
    }
}
