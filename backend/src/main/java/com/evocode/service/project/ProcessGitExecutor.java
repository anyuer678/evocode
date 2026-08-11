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
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new GitExecResult(-1, stdout, stderr, true);
        }
        if (!finished) {
            process.destroyForcibly();
            return new GitExecResult(-1, stdout, stderr, true);
        }
        return new GitExecResult(process.exitValue(), stdout, stderr, false);
    }
}
