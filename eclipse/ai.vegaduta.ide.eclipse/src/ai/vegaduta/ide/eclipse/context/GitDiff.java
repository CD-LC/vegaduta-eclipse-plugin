// "diff" context: `git diff HEAD` in the repository that holds the active file.
//
// Plain ProcessBuilder against the user's own git executable, deliberately NOT
// EGit/JGit: EGit is optional in Eclipse installs, and a Require-Bundle on it
// would make the whole plugin uninstallable wherever it is absent. Pure Java
// (no SWT, no workbench), so the whole thing is unit-tested against a real
// temporary repository.
//
// Threading: run() blocks for up to its timeout. It must ONLY be called from a
// background thread - WebviewBridge calls it on its executor, never on the
// SWT UI thread.
//
// Every failure is a typed reason string the chat UI shows next to the chip
// ("git is not installed", "not a git repository", ...), never an exception
// and never a silently empty diff.

package ai.vegaduta.ide.eclipse.context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class GitDiff {

    private GitDiff() {
    }

    /** Long enough for a large working tree on a slow disk, short enough that
     * a hung credential helper or a network filesystem cannot hold the chat
     * UI's "attaching..." state forever. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** Bytes of stdout kept. Four bytes per char covers any UTF-8, so this
     * always holds more than CONTEXT_ITEM_MAX_CHARS characters; the rest is
     * drained and discarded so git never blocks on a full pipe. */
    static final int MAX_CAPTURE_BYTES = ContextItems.MAX_CHARS * 4 + 4;

    /** Either {@code diff} (possibly empty = no changes) or {@code failure}. */
    public record Result(String diff, boolean overflowed, String failure) {
        static Result ok(String diff, boolean overflowed) {
            return new Result(diff, overflowed, null);
        }

        static Result failed(String reason) {
            return new Result(null, false, reason);
        }

        public boolean ok() {
            return failure == null;
        }
    }

    /**
     * Walks up from {@code start} to the directory containing ".git". A ".git"
     * FILE counts too: that is how worktrees and submodules point at their
     * real git directory. Returns null when {@code start} is not inside a
     * repository.
     */
    public static Path findGitRoot(Path start) {
        if (start == null) {
            return null;
        }
        Path dir = Files.isDirectory(start) ? start : start.getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** The exact command, exposed for the test that pins it. {@code --no-ext-diff}
     * and {@code --no-color} keep a user's git config from turning the output
     * into something that is not a unified diff; {@code core.quotepath=off}
     * keeps non-ASCII paths readable. */
    static List<String> command(String gitExecutable) {
        return List.of(gitExecutable, "-c", "core.quotepath=off", "--no-pager",
                "diff", "HEAD", "--no-color", "--no-ext-diff");
    }

    public static Result run(Path repoRoot) {
        return run(repoRoot, "git", DEFAULT_TIMEOUT);
    }

    /** Runs the diff. Blocking - background threads only (see header). */
    public static Result run(Path repoRoot, String gitExecutable, Duration timeout) {
        if (repoRoot == null) {
            return Result.failed("The active file is not inside a git repository.");
        }
        ProcessBuilder builder = new ProcessBuilder(command(gitExecutable));
        builder.directory(repoRoot.toFile());
        // Never let git stop and wait for a person: no pager, no credential or
        // terminal prompt. A diff needs neither, but a misconfigured global
        // setup should fail fast rather than hang until the timeout.
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
            // stdin is unused; failing to close it changes nothing.
        }
        Drain stdout = new Drain(process.getInputStream(), MAX_CAPTURE_BYTES);
        Drain stderr = new Drain(process.getErrorStream(), 8 * 1024);
        stdout.start();
        stderr.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Result.failed("Reading the git diff was cancelled.");
        }
        if (!finished) {
            process.destroyForcibly();
            return Result.failed("git diff did not finish within " + timeout.toSeconds() + " seconds.");
        }
        stdout.await(2000);
        stderr.await(2000);
        int exit = process.exitValue();
        if (exit != 0) {
            return Result.failed(classifyFailure(exit, stderr.text()));
        }
        return Result.ok(stdout.text(), stdout.overflowed());
    }

    /** Turns a non-zero git exit into a reason a person can act on. Pure, so
     * the wording is tested without having to provoke each failure. */
    static String classifyFailure(int exitCode, String stderr) {
        String err = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        if (err.contains("not a git repository")) {
            return "The active file is not inside a git repository.";
        }
        if (err.contains("ambiguous argument 'head'") || err.contains("bad revision 'head'")
                || err.contains("unknown revision")) {
            return "This repository has no commits yet, so there is nothing to diff against.";
        }
        if (err.contains("dubious ownership")) {
            return "git refused this repository (\"dubious ownership\") - mark it safe with"
                    + " git config --global --add safe.directory <path>.";
        }
        String firstLine = stderr == null ? "" : stderr.strip().lines().findFirst().orElse("");
        return firstLine.isEmpty()
                ? "git diff failed (exit code " + exitCode + ")."
                : "git diff failed: " + firstLine;
    }

    /** Reads a process stream to EOF on its own thread, keeping at most
     * {@code limit} bytes. Draining past the limit is what stops git from
     * blocking on a full pipe - and so from ever hitting the timeout on a
     * merely large diff. */
    static final class Drain extends Thread {
        private final InputStream in;
        private final int limit;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private volatile boolean overflowed;

        Drain(InputStream in, int limit) {
            super("vegaduta-git-drain");
            setDaemon(true);
            this.in = in;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    int room = limit - kept.size();
                    if (room > 0) {
                        kept.write(buffer, 0, Math.min(room, n));
                    }
                    if (n > room) {
                        overflowed = true;
                    }
                }
            } catch (IOException e) {
                // The process was destroyed (timeout) or closed its end early;
                // what was read so far is all there is.
            }
        }

        void await(long millis) {
            try {
                join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean overflowed() {
            return overflowed;
        }

        String text() {
            // ByteArrayOutputStream is internally synchronized, so this is safe
            // even if await() timed out. A cut can land inside a multi-byte
            // UTF-8 sequence; the decoder turns that tail into U+FFFD rather
            // than throwing.
            return new String(kept.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
