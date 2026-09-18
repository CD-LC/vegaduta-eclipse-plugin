// ContextAssembler: which items and which "missing" reasons a context.request
// produces for a given editor snapshot. git is replaced by a stub runner so
// each branch is reached deterministically; GitDiffTest covers real git.

package ai.vegaduta.ide.eclipse.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextAssemblerTest {

    @TempDir
    Path temp;

    private static final Function<Path, GitDiff.Result> NEVER = root -> {
        throw new AssertionError("git must not run here");
    };

    private static ContextAssembler.EditorSnapshot editor(String text, String selection, Path location,
            List<ContextItems.Problem> problems) {
        return new ContextAssembler.EditorSnapshot(true, "app/src/Main.java", text, "java", selection, location,
                List.of(), problems, problems == null ? "Problems are only tracked for files inside the Eclipse"
                        + " workspace." : null);
    }

    private static ContextAssembler.EditorSnapshot noEditor(List<Path> projects) {
        return new ContextAssembler.EditorSnapshot(false, null, null, null, null, null, projects, null, null);
    }

    private static String reason(ContextAssembler.Assembled a, String kind) {
        for (Map<String, Object> m : a.missing()) {
            if (kind.equals(m.get("kind"))) {
                return (String) m.get("reason");
            }
        }
        return null;
    }

    private Path repoWithFile() throws IOException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path file = repo.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}");
        return file;
    }

    @Test
    @DisplayName("file + selection come from the snapshot with the file's languageId")
    void fileAndSelection() {
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("file", "selection"), List.of(),
                editor("class Main {}\n", "Main", null, List.of()), NEVER);
        assertEquals(2, a.items().size());
        assertEquals("app/src/Main.java", a.items().get(0).get("label"));
        assertEquals("class Main {}\n", a.items().get(0).get("text"));
        assertEquals("java", a.items().get(0).get("languageId"));
        assertEquals("Selection (1 line)", a.items().get(1).get("label"));
        assertTrue(a.missing().isEmpty());
    }

    @Test
    @DisplayName("no active editor: every editor kind is missing with the same reason")
    void noEditorMissing() {
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("file", "selection", "diagnostics"),
                List.of(), noEditor(List.of()), NEVER);
        assertTrue(a.items().isEmpty());
        assertEquals(3, a.missing().size());
        assertEquals(ContextAssembler.NO_EDITOR, reason(a, "file"));
        assertEquals(ContextAssembler.NO_EDITOR, reason(a, "diagnostics"));
    }

    @Test
    @DisplayName("an empty selection is missing, not an empty item")
    void blankSelection() {
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("selection"), List.of(),
                editor("x", "   ", null, List.of()), NEVER);
        assertTrue(a.items().isEmpty());
        assertEquals("Nothing is selected in the active editor.", reason(a, "selection"));
    }

    @Test
    @DisplayName("a big file is truncated and flagged")
    void bigFileTruncated() {
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("file"), List.of(),
                editor("q".repeat(50_000), null, null, List.of()), NEVER);
        assertEquals(ContextItems.MAX_CHARS, ((String) a.items().get(0).get("text")).length());
        assertEquals(Boolean.TRUE, a.items().get(0).get("truncated"));
    }

    @Test
    @DisplayName("diagnostics: markers become one item; none is a 'no problems' reason; unreadable says why")
    void diagnostics() {
        ContextAssembler.Assembled some = ContextAssembler.assemble(List.of("diagnostics"), List.of(),
                editor("x", null, null, List.of(new ContextItems.Problem(2, 4, "Boom"))), NEVER);
        assertEquals("1 error", some.items().get(0).get("label"));
        assertTrue(((String) some.items().get(0).get("text")).contains("Error line 4: Boom"));

        ContextAssembler.Assembled none = ContextAssembler.assemble(List.of("diagnostics"), List.of(),
                editor("x", null, null, List.of()), NEVER);
        assertEquals("Eclipse reports no problems in app/src/Main.java.", reason(none, "diagnostics"));

        ContextAssembler.Assembled outside = ContextAssembler.assemble(List.of("diagnostics"), List.of(),
                editor("x", null, null, null), NEVER);
        assertEquals("Problems are only tracked for files inside the Eclipse workspace.",
                reason(outside, "diagnostics"));
    }

    @Test
    @DisplayName("diff runs in the active file's git root and becomes a labelled item")
    void diffFromActiveFile() throws IOException {
        Path file = repoWithFile();
        List<Path> ranIn = new ArrayList<>();
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("diff"), List.of(),
                editor("x", null, file, List.of()), root -> {
                    ranIn.add(root);
                    return GitDiff.Result.ok("diff --git a/x b/x\n@@ -1 +1 @@\n-a\n+b\n", false);
                });
        assertEquals(List.of(temp.resolve("repo")), ranIn);
        assertEquals("Working tree diff (1 file)", a.items().get(0).get("label"));
        assertEquals("diff", a.items().get(0).get("languageId"));
    }

    @Test
    @DisplayName("diff: a clean tree is 'no uncommitted changes', a git failure passes its reason through")
    void diffCleanAndFailure() throws IOException {
        Path file = repoWithFile();
        ContextAssembler.Assembled clean = ContextAssembler.assemble(List.of("diff"), List.of(),
                editor("x", null, file, List.of()), root -> GitDiff.Result.ok("", false));
        assertEquals("No uncommitted changes to tracked files in repo.", reason(clean, "diff"));

        ContextAssembler.Assembled failed = ContextAssembler.assemble(List.of("diff"), List.of(),
                editor("x", null, file, List.of()), root -> GitDiff.Result.failed("git is not installed"));
        assertEquals("git is not installed", reason(failed, "diff"));
    }

    @Test
    @DisplayName("diff: overflowed output is flagged truncated and the file count says it is a lower bound")
    void diffOverflow() throws IOException {
        Path file = repoWithFile();
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("diff"), List.of(),
                editor("x", null, file, List.of()),
                root -> GitDiff.Result.ok("diff --git a/x b/x\n+1\n", true));
        assertEquals(Boolean.TRUE, a.items().get(0).get("truncated"));
        assertEquals("Working tree diff (1+ files)", a.items().get(0).get("label"));
    }

    @Test
    @DisplayName("diff: a file outside any repository never runs git")
    void diffOutsideRepo() throws IOException {
        Path loose = temp.resolve("loose/Main.java");
        Files.createDirectories(loose.getParent());
        Files.writeString(loose, "x");
        Path probe = loose.getParent();
        while (probe != null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(!Files.exists(probe.resolve(".git")),
                    "temp dir is inside a git repository");
            probe = probe.getParent();
        }
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("diff"), List.of(),
                editor("x", null, loose, List.of()), NEVER);
        assertEquals("app/src/Main.java is not inside a git repository.", reason(a, "diff"));
    }

    @Test
    @DisplayName("diff with no editor: one shared repo is used, several are refused")
    void diffFallbackToProjects() throws IOException {
        Path repoA = temp.resolve("a");
        Files.createDirectories(repoA.resolve(".git"));
        Files.createDirectories(repoA.resolve("p1"));
        Files.createDirectories(repoA.resolve("p2"));
        List<Path> ranIn = new ArrayList<>();
        ContextAssembler.assemble(List.of("diff"), List.of(),
                noEditor(List.of(repoA.resolve("p1"), repoA.resolve("p2"))), root -> {
                    ranIn.add(root);
                    return GitDiff.Result.ok("", false);
                });
        assertEquals(List.of(repoA), ranIn);

        Path repoB = temp.resolve("b");
        Files.createDirectories(repoB.resolve(".git"));
        ContextAssembler.Assembled several = ContextAssembler.assemble(List.of("diff"), List.of(),
                noEditor(List.of(repoA, repoB)), NEVER);
        assertTrue(reason(several, "diff").startsWith("Several git repositories are open"));
    }

    @Test
    @DisplayName("an unsupported-but-valid kind ('page') is reported missing")
    void pageMissing() {
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of(), List.of("page"),
                noEditor(List.of()), NEVER);
        assertEquals("Not available in Eclipse.", reason(a, "page"));
    }

    // --- gitlog (wave 2) -----------------------------------------------------

    private static final Function<Path, GitLog.Result> NEVER_LOG = root -> {
        throw new AssertionError("git log must not run here");
    };

    private static String record(String hash, String date, String subject, String body) {
        return GitLog.RECORD + hash + GitLog.UNIT + date + GitLog.UNIT + subject + GitLog.UNIT + body + "\n";
    }

    @Test
    @DisplayName("gitlog: runs in the active file's repository and renders the commits")
    void gitlogFromActiveFile() throws IOException {
        Path file = repoWithFile();
        List<Path> ranIn = new ArrayList<>();
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("gitlog"), List.of(),
                editor("x", null, file, List.of()), NEVER, root -> {
                    ranIn.add(root);
                    return GitLog.Result.ok(record("abc1234", "2026-09-18", "feat: add login", "Why it matters.")
                            + record("def5678", "2026-09-17", "fix: typo", ""));
                });
        assertEquals(List.of(temp.resolve("repo")), ranIn);
        assertEquals(1, a.items().size());
        Map<String, Object> item = a.items().get(0);
        assertEquals("gitlog", item.get("kind"));
        assertEquals("Recent commits (2)", item.get("label"));
        String text = (String) item.get("text");
        assertTrue(text.startsWith("Last 2 commits in repo, newest first:"), text);
        assertTrue(text.contains("abc1234  2026-09-18  feat: add login\n    Why it matters.\n"), text);
        assertTrue(text.indexOf("abc1234") < text.indexOf("def5678"));
        assertEquals(null, item.get("truncated"));
    }

    @Test
    @DisplayName("gitlog: git failures and an empty history are reasons, never an empty item")
    void gitlogMissingReasons() throws IOException {
        Path file = repoWithFile();
        ContextAssembler.Assembled failed = ContextAssembler.assemble(List.of("gitlog"), List.of(),
                editor("x", null, file, List.of()), NEVER, root -> GitLog.Result.failed("git is not installed"));
        assertEquals("git is not installed", reason(failed, "gitlog"));
        assertTrue(failed.items().isEmpty());

        ContextAssembler.Assembled empty = ContextAssembler.assemble(List.of("gitlog"), List.of(),
                editor("x", null, file, List.of()), NEVER, root -> GitLog.Result.ok(""));
        assertEquals("This repository has no commits yet.", reason(empty, "gitlog"));
    }

    @Test
    @DisplayName("gitlog with no editor: several repositories are refused, not guessed between")
    void gitlogSeveralRepos() throws IOException {
        Path repoA = temp.resolve("a");
        Path repoB = temp.resolve("b");
        Files.createDirectories(repoA.resolve(".git"));
        Files.createDirectories(repoB.resolve(".git"));
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of("gitlog", "diff"), List.of(),
                noEditor(List.of(repoA, repoB)), NEVER, NEVER_LOG);
        assertTrue(reason(a, "gitlog").startsWith("Several git repositories are open"));
        assertTrue(reason(a, "diff").startsWith("Several git repositories are open"));
    }

    @Test
    @DisplayName("terminal and pageElements are valid kinds this host reports missing")
    void wave2UnsupportedKinds() {
        List<String> unsupported = new ArrayList<>();
        List<String> kinds = ContextItems.knownKinds(List.of("terminal", "pageElements", "gitlog"), unsupported);
        ContextAssembler.Assembled a = ContextAssembler.assemble(List.of(), unsupported, noEditor(List.of()),
                NEVER, NEVER_LOG);
        assertEquals(List.of("gitlog"), kinds);
        assertEquals("Not available in Eclipse.", reason(a, "terminal"));
        assertEquals("Not available in Eclipse.", reason(a, "pageElements"));
    }
}
