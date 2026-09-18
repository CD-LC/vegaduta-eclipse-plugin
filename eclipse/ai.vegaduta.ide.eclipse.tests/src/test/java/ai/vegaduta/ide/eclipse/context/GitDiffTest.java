// GitDiff against REAL git in temporary repositories: the command, the root
// walk (including a .git FILE, as worktrees and submodules have), the typed
// failures, and the drain that keeps a huge diff from blocking git.
//
// The tests that run git are skipped (not failed) on a machine without git on
// PATH - the same condition the plugin reports to the user as "git is not
// installed".

package ai.vegaduta.ide.eclipse.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffTest {

    @TempDir
    Path temp;

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        // Hermetic: no global hooks/signing/templates from the developer box.
        command.addAll(List.of("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-c", "init.defaultBranch=main"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "git timed out: " + command);
        assertEquals(0, p.exitValue(), "git failed: " + command + "\n" + out);
    }

    private Path committedRepo() throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("src"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("src/App.java"), "class App {\n}\n");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "init");
        return repo;
    }

    @Test
    @DisplayName("the command is a plain, colourless, pager-free git diff HEAD")
    void command() {
        assertEquals(List.of("git", "-c", "core.quotepath=off", "--no-pager", "diff", "HEAD", "--no-color",
                "--no-ext-diff"), GitDiff.command("git"));
    }

    @Test
    @DisplayName("findGitRoot walks up from a nested file to the directory holding .git")
    void findRootFromNestedFile() throws IOException {
        Path root = temp.resolve("r");
        Files.createDirectories(root.resolve(".git"));
        Path file = root.resolve("a/b/C.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        assertEquals(root, GitDiff.findGitRoot(file));
        assertEquals(root, GitDiff.findGitRoot(root.resolve("a")));
    }

    @Test
    @DisplayName("a .git FILE (worktree / submodule) marks a root too")
    void findRootGitFile() throws IOException {
        Path root = temp.resolve("wt");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/wt\n");
        assertEquals(root, GitDiff.findGitRoot(root.resolve("src")));
    }

    @Test
    @DisplayName("findGitRoot returns null outside any repository, and for null")
    void findRootNone() throws IOException {
        Path plain = temp.resolve("plain/dir");
        Files.createDirectories(plain);
        // A temp dir could in principle live under a repo; only assert when
        // its ancestors are clean.
        Path probe = plain;
        boolean ancestorIsRepo = false;
        while (probe != null) {
            if (Files.exists(probe.resolve(".git"))) {
                ancestorIsRepo = true;
            }
            probe = probe.getParent();
        }
        assumeTrue(!ancestorIsRepo, "temp dir is inside a git repository");
        assertNull(GitDiff.findGitRoot(plain));
        assertNull(GitDiff.findGitRoot(null));
    }

    @Test
    @DisplayName("failures classify into reasons a person can act on")
    void classifyFailure() {
        assertEquals("The active file is not inside a git repository.", GitDiff.classifyFailure(128,
                "fatal: not a git repository (or any of the parent directories): .git"));
        assertEquals("This repository has no commits yet, so there is nothing to diff against.",
                GitDiff.classifyFailure(128, "fatal: ambiguous argument 'HEAD': unknown revision or path not"
                        + " in the working tree."));
        assertTrue(GitDiff.classifyFailure(128, "fatal: detected dubious ownership in repository at '/x'")
                .contains("safe.directory"));
        assertEquals("git diff failed: fatal: something odd", GitDiff.classifyFailure(2,
                "\n fatal: something odd\nmore\n"));
        assertEquals("git diff failed (exit code 1).", GitDiff.classifyFailure(1, ""));
        assertEquals("git diff failed (exit code 1).", GitDiff.classifyFailure(1, null));
    }

    @Test
    @DisplayName("a missing git executable is a typed failure, not an exception")
    void missingExecutable() {
        GitDiff.Result result = GitDiff.run(temp, "vegaduta-no-such-git-binary", Duration.ofSeconds(5));
        assertFalse(result.ok());
        assertTrue(result.failure().startsWith("git is not installed"));
    }

    @Test
    @DisplayName("a null root is a typed failure")
    void nullRoot() {
        GitDiff.Result result = GitDiff.run(null);
        assertFalse(result.ok());
        assertEquals("The active file is not inside a git repository.", result.failure());
    }

    @Test
    @DisplayName("real repo: modified tracked files show up; a clean tree is an empty diff")
    void realDiff() throws Exception {
        assumeTrue(gitAvailable(), "git not on PATH");
        Path repo = committedRepo();

        GitDiff.Result clean = GitDiff.run(repo);
        assertTrue(clean.ok(), clean.failure());
        assertEquals("", clean.diff());

        Files.writeString(repo.resolve("src/App.java"), "class App {\n  int x;\n}\n");
        Files.writeString(repo.resolve("README.md"), "hello world\n");
        GitDiff.Result dirty = GitDiff.run(repo);
        assertTrue(dirty.ok(), dirty.failure());
        assertFalse(dirty.overflowed());
        assertTrue(dirty.diff().contains("+  int x;"), dirty.diff());
        assertEquals(2, ContextItems.diffFileCount(dirty.diff()));
    }

    @Test
    @DisplayName("real repo with no commits: typed 'no commits yet'")
    void realNoCommits() throws Exception {
        assumeTrue(gitAvailable(), "git not on PATH");
        Path repo = temp.resolve("empty");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        GitDiff.Result result = GitDiff.run(repo);
        assertFalse(result.ok());
        assertEquals("This repository has no commits yet, so there is nothing to diff against.",
                result.failure());
    }

    @Test
    @DisplayName("a huge diff is drained (git never blocks), capped, and flagged as overflowed")
    void realHugeDiff() throws Exception {
        assumeTrue(gitAvailable(), "git not on PATH");
        Path repo = committedRepo();
        // ~600 KB of changed lines: far over both the pipe buffer and the cap.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            big.append("line number ").append(i).append(" changed\n");
        }
        Files.writeString(repo.resolve("README.md"), big.toString());
        GitDiff.Result result = GitDiff.run(repo, "git", Duration.ofSeconds(60));
        assertTrue(result.ok(), result.failure());
        assertTrue(result.overflowed());
        assertTrue(result.diff().length() <= GitDiff.MAX_CAPTURE_BYTES);
        assertTrue(result.diff().length() > ContextItems.MAX_CHARS,
                "the kept part must still fill a whole context item");
    }
}
