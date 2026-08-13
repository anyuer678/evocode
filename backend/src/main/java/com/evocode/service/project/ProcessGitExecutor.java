package com.evocode.service.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ProcessBuilder 版 git 执行器（生产实现）。
 */
@Slf4j
@Component
public class ProcessGitExecutor implements GitExecutor {

    @Override
    public GitExecResult run(List<String> args, Path workDir, long timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workDir == null ? null : workDir.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        // 审查修复：先 waitFor(timeout) 再读输出——此前 readAllBytes 先执行，
        // 子进程不退出时会一直阻塞读 EOF，waitFor 的超时永不生效（超时失效）。
        // git clone 输出量小（进度在 stderr），管道缓冲不易满，先等后读安全。
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new GitExecResult(-1, readQuietly(process.getInputStream()),
                    readQuietly(process.getErrorStream()), true);
        }
        if (!finished) {
            process.destroyForcibly();
            String stdout = readQuietly(process.getInputStream());
            String stderr = readQuietly(process.getErrorStream());
            return new GitExecResult(-1, stdout, stderr, true);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new GitExecResult(process.exitValue(), stdout, stderr, false);
    }

    /** 进程被强杀后读残留输出（读 EOF 即返回，不会阻塞）。 */
    private static String readQuietly(java.io.InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }
}
