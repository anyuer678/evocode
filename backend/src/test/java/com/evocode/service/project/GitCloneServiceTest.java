package com.evocode.service.project;

import com.evocode.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-U-23~26：克隆成功 / 私有仓库 / 超时 / cloneDepth 参数。
 * 通过可注入的 GitExecutor 模拟 git 行为。
 */
class GitCloneServiceTest {

    @TempDir
    Path tempDir;

    /** 记录最近一次执行参数的 fake executor；成功时创建 targetDir。 */
    private final FakeGitExecutor executor = new FakeGitExecutor();

    private GitCloneService newService() {
        return new GitCloneServiceImpl(executor, "git", 300, "", 500L * 1024 * 1024);
    }

    @Test
    void cloneSuccessWithDepthOne() {
        GitCloneService service = newService();
        Path target = tempDir.resolve("repo");
        service.clone("https://github.com/owner/repo", 1, target);
        assertTrue(Files.exists(target), "克隆成功后目录应存在");
        assertTrue(executor.lastArgs.contains("--depth"));
        assertEquals("1", executor.lastArgs.get(executor.lastArgs.indexOf("--depth") + 1));
        assertTrue(executor.lastArgs.contains("https://github.com/owner/repo"));
        assertTrue(executor.lastArgs.contains(target.toString()));
    }

    @Test
    void cloneDepthZeroIsFullCloneWithoutDepthFlag() {
        GitCloneService service = newService();
        Path target = tempDir.resolve("repo");
        service.clone("https://github.com/owner/repo", 0, target);
        assertFalse(executor.lastArgs.contains("--depth"), "depth=0 全量克隆不应带 --depth");
    }

    @Test
    void privateOrMissingRepoReturnsCloneFailed() {
        executor.exitCode = 128;
        GitCloneService service = newService();
        Path target = tempDir.resolve("repo");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.clone("https://github.com/owner/missing", 1, target));
        assertEquals(2009, e.getCode());
        assertTrue(e.getMessage().contains("不存在或为私有"));
    }

    @Test
    void timeoutReturnsCloneFailedAndCleansTarget() throws Exception {
        executor.timedOut = true;
        GitCloneService service = newService();
        Path target = tempDir.resolve("repo");
        Files.createDirectories(target);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.clone("https://github.com/owner/slow", 1, target));
        assertEquals(2009, e.getCode());
        assertTrue(e.getMessage().contains("超时"));
        assertFalse(Files.exists(target), "克隆超时后临时目录应清理");
    }

    @Test
    void invalidUrlRejectedAsParamError() {
        GitCloneService service = newService();
        assertThrows(BusinessException.class,
                () -> service.clone("not-a-url", 1, tempDir.resolve("r")));
        assertThrows(BusinessException.class,
                () -> service.clone("https://example.com/plain", 1, tempDir.resolve("r")));
        assertThrows(BusinessException.class,
                () -> service.clone("https://github.com/only-owner", 1, tempDir.resolve("r")));
    }

    static class FakeGitExecutor implements GitExecutor {
        int exitCode = 0;
        boolean timedOut = false;
        List<String> lastArgs;

        @Override
        public GitExecResult run(List<String> args, Path workDir, long timeoutSeconds) throws IOException {
            this.lastArgs = args;
            if (!timedOut && exitCode == 0) {
                Path target = Path.of(args.get(args.size() - 1));
                Files.createDirectories(target);
            }
            return new GitExecResult(exitCode, "", timedOut ? "timed out" : "", timedOut);
        }
    }
}
