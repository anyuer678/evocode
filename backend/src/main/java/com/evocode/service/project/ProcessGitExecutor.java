package com.evocode.service.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        // 审查修复：读线程与 waitFor 并行——① 先启动 stdout/stderr 读线程持续排空管道，
        // 防子进程输出超过管道缓冲（64KB）时阻塞写导致 waitFor 永不返回（此前 readAllBytes
        // 先于 waitFor 是超时失效；上一轮改的「先 waitFor 后读」则会误杀大输出正常 clone）。
        // ② waitFor(timeout) 判定超时，到点 destroyForcibly 后读线程读到 EOF 自然收尾。
        StreamDrain stdoutDrain = new StreamDrain(process.getInputStream(), "git-out");
        StreamDrain stderrDrain = new StreamDrain(process.getErrorStream(), "git-err");
        stdoutDrain.start();
        stderrDrain.start();
        try {
            boolean finished;
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new GitExecResult(-1, stdoutDrain.await(), stderrDrain.await(), true);
            }
            if (!finished) {
                process.destroyForcibly();
            }
            // 进程已退出或已被强杀：读线程 readAllBytes 读到 EOF 必然收尾，join 等待完成
            String stdout = stdoutDrain.await();
            String stderr = stderrDrain.await();
            return new GitExecResult(finished ? process.exitValue() : -1, stdout, stderr, !finished);
        } finally {
            process.destroyForcibly();
        }
    }

    /** 后台排空一个输入流；await() join 线程后返回全部内容（读到 EOF）。 */
    private static final class StreamDrain implements Runnable {
        private final InputStream in;
        private final String name;
        private Thread thread;

        StreamDrain(InputStream in, String name) {
            this.in = in;
            this.name = name;
        }

        void start() {
            thread = new Thread(this, name);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            try {
                in.readAllBytes(); // 持续排空管道；EOF 返回
            } catch (IOException ignored) {
                // 进程被强杀时管道关闭，readAllBytes 抛异常或返回均可
            }
        }

        String await() {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 输出内容不需要——git clone 成功/失败判定只依赖 exitCode；错误信息在 stderr，
            // 由调用方以 exitCode 判定（不展示原始输出）。保留读线程仅为防管道阻塞。
            return "";
        }
    }
}
