// GitLog: the pinned command, parsing git's separator-delimited output, the
// rendering and its commit-boundary truncation, failure wording, and a run
// against REAL git in a temporary repository (skipped - not failed - where git
// is not on PATH, the same condition the plugin reports as "git is not
// installed").

package ai.vegaduta.ide.eclipse.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class GitLogTest {

    @TempDir
    Path temp;

    private static String record(String hash, String date, String subject, String body) {
        return GitLog.RECORD + hash + GitLog.UNIT + date + GitLog.UNIT + subject + GitLog.UNIT + body + "\n";
    }

    @Test
    @DisplayName("command: 30 commits, short dates, separator format, no pager/colour/signatures")
    void command() {
        assertEquals(List.of("git", "-c", "core.quotepath=off", "-c", "log.showSignature=false", "--no-pager",
                "log", "-n", "30", "--no-color", "--date=short", "--format=%x1e%h%x1f%ad%x1f%s%x1f%b"),
                GitLog.command("git"));
        assertEquals(30, GitLog.COMMIT_COUNT);
    }

    @Test
    @DisplayName("parse: fields split on the unit separator; CRLF bodies normalized; hashless records dropped")
    void parse() {
        String output = record("abc1234", "2026-09-18", "feat: add | pipes | safely", "Line one\r\nLine two\r\n")
                + record("def5678", "2026-09-17", "fix: x", "")
                + GitLog.RECORD + GitLog.UNIT + "2026-01-01" + GitLog.UNIT + "no hash" + GitLog.UNIT + "\n";
        List<GitLog.Commit> commits = GitLog.parse(output);
        assertEquals(2, commits.size());
        assertEquals(new GitLog.Commit("abc1234", "2026-09-18", "feat: add | pipes | safely", "Line one\nLine two"),
                commits.get(0));
        assertEquals(new GitLog.Commit("def5678", "2026-09-17", "fix: x", ""), commits.get(1));
        assertTrue(GitLog.parse("").isEmpty());
        assertTrue(GitLog.parse(null).isEmpty());
    }

    @Test
    @DisplayName("parse: a record cut off by the capture limit still yields what it has")
    void parseCutRecord() {
        String cut = record("abc1234", "2026-09-18", "feat: a", "") + GitLog.RECORD + "def5678" + GitLog.UNIT
                + "2026-09-1";
        List<GitLog.Commit> commits = GitLog.parse(cut);
        assertEquals(2, commits.size());
        assertEquals(new GitLog.Commit("def5678", "2026-09-1", "", ""), commits.get(1));
    }

    @Test
    @DisplayName("render: header line, then each commit with its body indented four spaces")
    void render() {
        GitLog.Rendered r = GitLog.render(List.of(
                new GitLog.Commit("abc1234", "2026-09-18", "feat: login", "Why:\n\nBecause."),
                new GitLog.Commit("def5678", "2026-09-17", "", "")), "app");
        assertEquals("Last 2 commits in app, newest first:\n\n"
                + "abc1234  2026-09-18  feat: login\n    Why:\n\n    Because.\n\n"
                + "def5678  2026-09-17  (no subject)\n", r.text());
        assertEquals(2, r.shown());
        assertFalse(r.truncated());
        assertEquals("Recent commits (2)", GitLog.label(r));
    }

    @Test
    @DisplayName("render: a huge body is capped per commit so it cannot crowd out the rest")
    void bodyCap() {
        String body = "x".repeat(GitLog.MAX_BODY_CHARS * 3);
        String block = GitLog.renderCommit(new GitLog.Commit("abc1234", "2026-09-18", "big", body));
        assertTrue(block.contains("[... body cut]"));
        assertTrue(block.length() < GitLog.MAX_BODY_CHARS + 200, "block length " + block.length());
    }

    @Test
    @DisplayName("render: truncation cuts at a whole commit, never exceeds the limit, and says so")
    void truncatesAtCommitBoundary() {
        List<GitLog.Commit> commits = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            commits.add(new GitLog.Commit(String.format("h%06d", i), "2026-09-18", "subject " + i,
                    "body ".repeat(250)));
        }
        GitLog.Rendered r = GitLog.render(commits, "app", 6_000);
        assertTrue(r.text().length() <= 6_000, "length " + r.text().length());
        assertTrue(r.truncated());
        assertTrue(r.shown() > 0 && r.shown() < 30);
        assertTrue(r.text().startsWith("Last " + r.shown() + " of the last 30 commits in app"), r.text());
        // The last commit shown is complete: its body's final word is there.
        String lastHash = String.format("h%06d", r.shown() - 1);
        assertTrue(r.text().contains(lastHash));
        assertFalse(r.text().contains(String.format("h%06d", r.shown())));
        assertTrue(r.text().endsWith("body\n"), r.text().substring(r.text().length() - 40));
        assertEquals("Recent commits (" + r.shown() + " of 30)", GitLog.label(r));

        // Everything fits at the real limit.
        GitLog.Rendered all = GitLog.render(commits.subList(0, 5), "app");
        assertFalse(all.truncated());
        assertEquals(5, all.shown());
    }

    @Test
    @DisplayName("render: a single commit larger than the limit is cut by characters and flagged")
    void singleOversizedCommit() {
        GitLog.Rendered r = GitLog.render(
                List.of(new GitLog.Commit("abc1234", "2026-09-18", "s".repeat(500), "")), "app", 200);
        assertEquals(200, r.text().length());
        assertTrue(r.truncated());
        assertEquals(1, r.shown());
    }

    @Test
    @DisplayName("classifyFailure: actionable wording for the common git errors")
    void classifyFailure() {
        assertEquals("The active file is not inside a git repository.",
                GitLog.classifyFailure(128, "fatal: not a git repository (or any of the parent directories)"));
        assertEquals("This repository has no commits yet.", GitLog.classifyFailure(128,
                "fatal: your current branch 'main' does not have any commits yet"));
        assertTrue(GitLog.classifyFailure(128, "fatal: detected dubious ownership in repository")
                .contains("safe.directory"));
        assertEquals("git log failed: boom", GitLog.classifyFailure(1, "boom\nmore"));
        assertEquals("git log failed (exit code 3).", GitLog.classifyFailure(3, ""));
    }

    @Test
    @DisplayName("a missing git executable is a reason, not an exception")
    void missingExecutable() {
        GitLog.Result r = GitLog.run(temp, "definitely-not-git-" + System.nanoTime(), Duration.ofSeconds(5));
        assertFalse(r.ok());
        assertTrue(r.failure().startsWith("git is not installed"));
        assertFalse(GitLog.run(null).ok());
    }

    // --- real git ------------------------------------------------------------

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
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Test",
                "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false",
                "-c", "init.defaultBranch=main"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "git timed out: " + command);
        assertEquals(0, p.exitValue(), "git failed: " + command + "\n" + out);
    }

    @Test
    @DisplayName("real git: commits come back newest first with subjects and bodies, no author")
    void realLog() throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        for (int i = 1; i <= 3; i++) {
            Files.writeString(repo.resolve("f.txt"), "v" + i);
            git(repo, "add", "f.txt");
            git(repo, "commit", "-q", "-m", "feat: step " + i, "-m", "Body of step " + i + ".");
        }
        GitLog.Result result = GitLog.run(repo);
        assertTrue(result.ok(), result.failure());
        List<GitLog.Commit> commits = GitLog.parse(result.output());
        assertEquals(3, commits.size());
        assertEquals("feat: step 3", commits.get(0).subject());
        assertEquals("Body of step 3.", commits.get(0).body());
        assertEquals("feat: step 1", commits.get(2).subject());
        assertTrue(commits.get(0).date().matches("\\d{4}-\\d{2}-\\d{2}"), commits.get(0).date());
        assertFalse(result.output().contains("test@example.invalid"));
        assertFalse(GitLog.render(commits, "repo").text().contains("Test"));
    }

    @Test
    @DisplayName("real git: a repository with no commits is a clear reason")
    void realNoCommits() throws Exception {
        assumeTrue(gitAvailable(), "git is not on PATH");
        Path repo = temp.resolve("empty");
        Files.createDirectories(repo);
        git(repo, "init", "-q");
        GitLog.Result result = GitLog.run(repo);
        assertFalse(result.ok());
        assertEquals("This repository has no commits yet.", result.failure());
    }
}
