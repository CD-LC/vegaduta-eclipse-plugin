// "gitlog" context: the last 30 commits (subject + body) of the repository
// that holds the active file - so a commit message, release note or standup
// can follow the team's own style.
//
// Same rules as GitDiff: the user's own git executable through ProcessBuilder
// (no EGit/JGit dependency), a timeout, stdout capped and drained so git never
// blocks on a full pipe, and every failure a typed reason string rather than
// an exception. BLOCKING - call run() from a background thread only; the
// bridge runs it on its executor, never on the SWT UI thread.
//
// What is sent: short hash, commit date, subject and body. NOT the author name
// or e-mail - the style of the messages is the point, and who wrote them is
// personal data the model does not need.
//
// Parsing and rendering are pure and unit-tested (GitLogTest), including the
// truncation, which cuts at a COMMIT boundary so the model never sees half a
// message presented as a whole one.

package ai.vegaduta.ide.eclipse.context;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class GitLog {

    private GitLog() {
    }

    /** How many commits are asked for (protocol.ts: "git log -n 30"). */
    public static final int COMMIT_COUNT = 30;

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** One body is capped so a single enormous message (a squash of a whole
     * branch, say) cannot crowd every other commit out of the item. */
    static final int MAX_BODY_CHARS = 1_500;

    /** Bytes of git's stdout kept; enough for 30 capped commits many times
     * over, and the rest is drained and dropped. */
    static final int MAX_CAPTURE_BYTES = ContextItems.MAX_CHARS * 4 + 4;

    /** ASCII record / unit separators: they cannot appear in a subject typed
     * at a keyboard, so splitting on them is unambiguous where splitting on
     * newlines or "|" is not. */
    static final char RECORD = (char) 0x1e;
    static final char UNIT = (char) 0x1f;

    /** One parsed commit. */
    public record Commit(String hash, String date, String subject, String body) {
    }

    /** Either {@code output} (possibly empty = no commits) or {@code failure}. */
    public record Result(String output, String failure) {
        static Result ok(String output) {
            return new Result(output, null);
        }

        static Result failed(String reason) {
            return new Result(null, reason);
        }

        public boolean ok() {
            return failure == null;
        }
    }

    /** What the context item carries: the rendered text, how many commits it
     * holds, and whether any were left out to fit the size limit. */
    public record Rendered(String text, int shown, int total, boolean truncated) {
    }

    /** The exact command, pinned by a test. {@code %x1e}/{@code %x1f} are
     * git's escapes for the separators above; {@code log.showSignature=false}
     * keeps a user's log.showSignature config from mixing GPG output in. */
    static List<String> command(String gitExecutable) {
        return List.of(gitExecutable, "-c", "core.quotepath=off", "-c", "log.showSignature=false",
                "--no-pager", "log", "-n", String.valueOf(COMMIT_COUNT), "--no-color", "--date=short",
                "--format=%x1e%h%x1f%ad%x1f%s%x1f%b");
    }

    public static Result run(Path repoRoot) {
        return run(repoRoot, "git", DEFAULT_TIMEOUT);
    }

    /** Runs git log. Blocking - background threads only (see header). */
    public static Result run(Path repoRoot, String gitExecutable, Duration timeout) {
        if (repoRoot == null) {
            return Result.failed("The active file is not inside a git repository.");
        }
        ProcessBuilder builder = new ProcessBuilder(command(gitExecutable));
        builder.directory(repoRoot.toFile());
        builder.environment().put("GIT_PAGER", "cat");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("LC_ALL", "C");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return Result.failed("git is not installed or is not on the PATH Eclipse was started with.");
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // stdin is unused.
        }
        GitDiff.Drain stdout = new GitDiff.Drain(process.getInputStream(), MAX_CAPTURE_BYTES);
        GitDiff.Drain stderr = new GitDiff.Drain(process.getErrorStream(), 8 * 1024);
        stdout.start();
        stderr.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Result.failed("Reading the git log was cancelled.");
        }
        if (!finished) {
            process.destroyForcibly();
            return Result.failed("git log did not finish within " + timeout.toSeconds() + " seconds.");
        }
        stdout.await(2000);
        stderr.await(2000);
        int exit = process.exitValue();
        if (exit != 0) {
            return Result.failed(classifyFailure(exit, stderr.text()));
        }
        return Result.ok(stdout.text());
    }

    /** A non-zero git exit as a reason a person can act on. */
    static String classifyFailure(int exitCode, String stderr) {
        String err = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (err.contains("not a git repository")) {
            return "The active file is not inside a git repository.";
        }
        if (err.contains("does not have any commits yet") || err.contains("bad default revision")
                || err.contains("unknown revision")) {
            return "This repository has no commits yet.";
        }
        if (err.contains("dubious ownership")) {
            return "git refused this repository (\"dubious ownership\") - mark it safe with"
                    + " git config --global --add safe.directory <path>.";
        }
        String firstLine = stderr == null ? "" : stderr.strip().lines().findFirst().orElse("");
        return firstLine.isEmpty()
                ? "git log failed (exit code " + exitCode + ")."
                : "git log failed: " + firstLine;
    }

    /**
     * Splits git's output into commits. A record cut off by the capture limit
     * is still parsed (its missing fields are empty); a record with no hash is
     * dropped. Never throws.
     */
    public static List<Commit> parse(String output) {
        List<Commit> commits = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return commits;
        }
        for (String record : output.split(String.valueOf(RECORD), -1)) {
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.split(String.valueOf(UNIT), 4);
            String hash = fields[0].strip();
            if (hash.isEmpty()) {
                continue;
            }
            String date = fields.length > 1 ? fields[1].strip() : "";
            String subject = fields.length > 2 ? fields[2].strip() : "";
            String body = fields.length > 3 ? fields[3].replace("\r\n", "\n").strip() : "";
            commits.add(new Commit(hash, date, subject, body));
        }
        return commits;
    }

    /** Renders one commit: header line, then the body indented four spaces. */
    static String renderCommit(Commit commit) {
        StringBuilder out = new StringBuilder();
        out.append(commit.hash());
        if (!commit.date().isEmpty()) {
            out.append("  ").append(commit.date());
        }
        out.append("  ").append(commit.subject().isEmpty() ? "(no subject)" : commit.subject()).append('\n');
        String body = commit.body();
        if (!body.isEmpty()) {
            if (body.length() > MAX_BODY_CHARS) {
                body = ContextItems.truncate(body, MAX_BODY_CHARS).text().stripTrailing() + "\n[... body cut]";
            }
            for (String line : body.split("\n", -1)) {
                out.append(line.isEmpty() ? "" : "    " + line).append('\n');
            }
        }
        out.append('\n');
        return out.toString();
    }

    /**
     * Renders commits newest first, stopping at the last WHOLE commit that
     * fits in {@code maxChars}. If even the first commit does not fit, it is
     * cut by characters (so the item is never empty when git returned
     * something) and flagged truncated.
     */
    public static Rendered render(List<Commit> commits, String repoName, int maxChars) {
        String in = (repoName == null || repoName.isBlank()) ? "" : " in " + repoName;
        // Sized for the LONGEST header this can produce, so the fit check
        // below never lets the final text exceed maxChars.
        int headerRoom = header(commits.size(), commits.size() + 1, in).length();
        StringBuilder blocks = new StringBuilder();
        int shown = 0;
        for (Commit commit : commits) {
            String block = renderCommit(commit);
            if (headerRoom + blocks.length() + block.length() > maxChars) {
                if (shown == 0) {
                    String cut = header(1, commits.size(), in) + block;
                    return new Rendered(ContextItems.truncate(cut, maxChars).text(), 1, commits.size(), true);
                }
                break;
            }
            blocks.append(block);
            shown++;
        }
        String text = (header(shown, commits.size(), in) + blocks).stripTrailing() + "\n";
        return new Rendered(text, shown, commits.size(), shown < commits.size());
    }

    private static String header(int shown, int total, String in) {
        String count = shown < total ? shown + " of the last " + total : String.valueOf(total);
        return "Last " + count + (total == 1 ? " commit" : " commits") + in + ", newest first:\n\n";
    }

    public static Rendered render(List<Commit> commits, String repoName) {
        return render(commits, repoName, ContextItems.MAX_CHARS);
    }

    /** "Recent commits (30)" / "Recent commits (12 of 30)". */
    public static String label(Rendered rendered) {
        if (rendered.shown() < rendered.total()) {
            return "Recent commits (" + rendered.shown() + " of " + rendered.total() + ")";
        }
        return "Recent commits (" + rendered.total() + ")";
    }
}
