package com.evocode.service.project;

import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * git clone 实现（06 §3.1 方式 B；错误码 2009）。
 */
@Slf4j
@Service
public class GitCloneServiceImpl implements GitCloneService {

    private static final Pattern REPO_URL =
            Pattern.compile("^https://(github\\.com|gitee\\.com|gitlab\\.com|gitcode\\.com)/[^/\\s]+/[^/\\s]+/?$");

    private final GitExecutor gitExecutor;
    private final String gitExecutable;
    private final long timeoutSeconds;
    private final String gitProxy;
    private final long maxExtractBytes;

    @Autowired
    public GitCloneServiceImpl(GitExecutor gitExecutor,
                               com.evocode.config.EvocodeProperties props) {
        this(gitExecutor, props.getGitExecutable(), props.getGitCloneTimeoutSeconds(),
                props.getGitProxy(), props.getUploadMaxExtractBytes());
    }

    /** 可注入执行器与超时，便于单测（T-U-23~26）。 */
    GitCloneServiceImpl(GitExecutor gitExecutor, String gitExecutable, long timeoutSeconds,
                        String gitProxy, long maxExtractBytes) {
        this.gitExecutor = gitExecutor;
        this.gitExecutable = gitExecutable;
        this.timeoutSeconds = timeoutSeconds;
        this.gitProxy = gitProxy == null ? "" : gitProxy.trim();
        this.maxExtractBytes = maxExtractBytes;
    }

    @Override
    public void clone(String repoUrl, int cloneDepth, Path targetDir) {
        String url = repoUrl == null ? "" : repoUrl.trim();
        if (!REPO_URL.matcher(url).matches()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "repoUrl 格式非法（需 https://github.com/owner/repo）");
        }
        List<String> args = new ArrayList<>();
        args.add(gitExecutable);
        args.add("clone");
        if (cloneDepth > 0) {
            args.add("--depth");
            args.add(String.valueOf(cloneDepth));
        }
        if (!gitProxy.isEmpty()) {
            args.add("--config");
            args.add("http.proxy=" + gitProxy);
        }
        args.add(url);
        args.add(targetDir.toString());

        GitExecutor.GitExecResult result;
        try {
            result = gitExecutor.run(args, targetDir.getParent(), timeoutSeconds);
        } catch (IOException e) {
            log.error("git 执行失败", e);
            cleanup(targetDir);
            throw new BusinessException(ErrorCode.GIT_CLONE_FAILED, "仓库克隆失败：" + e.getMessage());
        }
        if (result.timedOut()) {
            cleanup(targetDir);
            throw new BusinessException(ErrorCode.GIT_CLONE_FAILED, "仓库克隆失败：克隆超时（" + timeoutSeconds + "s）");
        }
        if (result.exitCode() != 0) {
            cleanup(targetDir);
            if (result.exitCode() == 128) {
                throw new BusinessException(ErrorCode.GIT_CLONE_FAILED, "仓库克隆失败：仓库不存在或为私有");
            }
            throw new BusinessException(ErrorCode.GIT_CLONE_FAILED,
                    "仓库克隆失败（exit=" + result.exitCode() + "）");
        }
        // 审查 L2：克隆后落盘大小上限（同 zip 解压 500MB），防巨型仓库磁盘/网络 DoS
        long size;
        try {
            size = Files.walk(targetDir).filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            size = 0;
        }
        if (size > maxExtractBytes) {
            cleanup(targetDir);
            throw new BusinessException(ErrorCode.FILE_ILLEGAL,
                    "克隆后仓库超过体积上限 " + (maxExtractBytes / 1024 / 1024) + "MB");
        }
    }

    private void cleanup(Path targetDir) {
        try {
            if (Files.exists(targetDir)) {
                Files.walk(targetDir)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (IOException e) {
            log.warn("克隆临时目录清理失败: {}", targetDir, e);
        }
    }
}
