package com.evocode.service.project;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * zip 上传校验与解压（06 §3.1 校验顺序 ①-④；FR-1.1）。
 */
public interface UploadService {

    /**
     * 校验并解压到临时目录，返回项目根目录。
     * 失败时清理临时目录并抛 BusinessException(2003)。
     * 单层顶层目录自动上移一层作为项目根（FR-1.1）。
     */
    Path extractZip(Path tempDir, MultipartFile file);
}
