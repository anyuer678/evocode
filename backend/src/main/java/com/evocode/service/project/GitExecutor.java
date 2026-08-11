package com.evocode.service.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * git 子进程执行抽象（可注入，单测模拟；生产用 ProcessBuilder 实现）。
 */
public interface GitExecutor {

    record GitExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }

    GitExecResult run(List<String> args, Path workDir, long timeoutSeconds) throws IOException;
}
