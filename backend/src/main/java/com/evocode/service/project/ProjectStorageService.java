package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.config.EvocodeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 项目磁盘存储（AD-6：磁盘存代码，DB 存元数据）。
 * 从 ProjectServiceImpl 抽出的纯文件系统能力：临时目录、项目存储路径推导、
 * 目录原子搬移（同盘 move / 跨盘 copy+delete 回退）与递归清理。无 DB 依赖。
 */
@Slf4j
@Service
public class ProjectStorageService {

    private final EvocodeProperties props;

    public ProjectStorageService(EvocodeProperties props) {
        this.props = props;
    }

    public Path storagePathOf(Long projectId) {
        return Path.of(props.getDataDir(), "projects", String.valueOf(projectId));
    }

    public String relStoragePath(Long projectId) {
        // 审查修复：与 storagePathOf 完全一致（dataDir 感知）——此前硬编码 "data/projects/"，DB
        // 存的相对路径在 DATA_DIR 自定义后与磁盘实际路径脱节，FileController/ChatStreamer 等
        // 以 Path.of(storagePath) 解析会指向错误位置。
        return storagePathOf(projectId).toString();
    }

    public Path createTempDir() {
        try {
            return Files.createTempDirectory("evocode-upload-");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_ILLEGAL, "临时目录创建失败：" + e.getMessage());
        }
    }

    /** 同盘 move；跨盘回退 copy+delete。 */
    public void moveDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            copyRecursive(src, dst);
            deleteRecursive(src);
        }
    }

    public void copyRecursive(Path src, Path dst) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : stream.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public void deleteRecursive(Path root) {
        try {
            if (root != null && Files.exists(root)) {
                Files.walk(root)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("目录清理失败: {}", root, e);
        }
    }
}
