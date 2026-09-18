// The pure half of context.request: truncation to CONTEXT_ITEM_MAX_CHARS,
// chip labels, problem-marker rendering, kind filtering, and the
// capabilities this host advertises in init.

package ai.vegaduta.ide.eclipse.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class ContextItemsTest {

    @Test
    @DisplayName("MAX_CHARS mirrors protocol.ts CONTEXT_ITEM_MAX_CHARS")
    void maxCharsMatchesProtocol() {
        assertEquals(24_000, ContextItems.MAX_CHARS);
    }

    @Test
    @DisplayName("text at or under the limit is untouched and not flagged")
    void truncateUnderLimit() {
        String exact = "x".repeat(ContextItems.MAX_CHARS);
        ContextItems.Truncated cut = ContextItems.truncate(exact);
        assertEquals(exact, cut.text());
        assertFalse(cut.truncated());
    }

    @Test
    @DisplayName("text over the limit is cut to exactly the limit and flagged")
    void truncateOverLimit() {
        ContextItems.Truncated cut = ContextItems.truncate("y".repeat(ContextItems.MAX_CHARS + 500));
        assertEquals(ContextItems.MAX_CHARS, cut.text().length());
        assertTrue(cut.truncated());
    }

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    void truncateKeepsSurrogatesWhole() {
        // "a" + U+1F600 (two UTF-16 units) + "b": a cut at 2 would leave a
        // lone high surrogate.
        String text = "a😀b";
        ContextItems.Truncated cut = ContextItems.truncate(text, 2);
        assertEquals("a", cut.text());
        assertTrue(cut.truncated());
        assertEquals("a😀", ContextItems.truncate(text, 3).text());
    }

    @Test
    @DisplayName("null text truncates to empty, unflagged")
    void truncateNull() {
        ContextItems.Truncated cut = ContextItems.truncate(null);
        assertEquals("", cut.text());
        assertFalse(cut.truncated());
    }

    @Test
    @DisplayName("item() truncates and only sets truncated when it did")
    void itemTruncates() {
        Map<String, Object> small = ContextItems.item("file", "p/A.java", "class A {}", "java");
        assertEquals("file", small.get("kind"));
        assertEquals("p/A.java", small.get("label"));
        assertEquals("java", small.get("languageId"));
        assertNull(small.get("truncated"));

        Map<String, Object> big = ContextItems.item("file", "p/B.java", "z".repeat(30_000), null);
        assertEquals(ContextItems.MAX_CHARS, ((String) big.get("text")).length());
        assertEquals(Boolean.TRUE, big.get("truncated"));
        // Absent languageId is omitted on the wire, not written as null.
        assertFalse(Json.write(big).contains("languageId"));
    }

    @Test
    @DisplayName("line counts ignore a trailing newline")
    void lineCount() {
        assertEquals(0, ContextItems.lineCount(""));
        assertEquals(1, ContextItems.lineCount("one"));
        assertEquals(1, ContextItems.lineCount("one\n"));
        assertEquals(2, ContextItems.lineCount("one\ntwo"));
        assertEquals(3, ContextItems.lineCount("a\n\nc\n"));
        assertEquals("Selection (1 line)", ContextItems.selectionLabel("x"));
        assertEquals("Selection (12 lines)", ContextItems.selectionLabel("l\n".repeat(12)));
    }

    @Test
    @DisplayName("diff labels count 'diff --git' headers")
    void diffLabel() {
        String diff = "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-a\n+b\n"
                + "diff --git a/B.md b/B.md\n--- a/B.md\n+++ b/B.md\n@@ -1 +1 @@\n-x\n+ diff --git not a header\n";
        assertEquals(2, ContextItems.diffFileCount(diff));
        assertEquals("Working tree diff (2 files)", ContextItems.diffLabel(diff));
        assertEquals("Working tree diff (1 file)",
                ContextItems.diffLabel("diff --git a/x b/x\n@@ -1 +1 @@\n-1\n+2\n"));
        assertEquals(0, ContextItems.diffFileCount(null));
    }

    @Test
    @DisplayName("problems render errors first, then by line, one per line")
    void formatProblems() {
        List<ContextItems.Problem> problems = List.of(
                new ContextItems.Problem(ContextItems.SEVERITY_WARNING, 3, "Unused import"),
                new ContextItems.Problem(ContextItems.SEVERITY_ERROR, 20, "Type mismatch"),
                new ContextItems.Problem(ContextItems.SEVERITY_ERROR, 7, "Syntax error\non token"),
                new ContextItems.Problem(ContextItems.SEVERITY_INFO, -1, "  "));
        String text = ContextItems.formatProblems("app/src/Main.java", problems);
        assertEquals("Problems reported by Eclipse for app/src/Main.java:\n"
                + "Error line 7: Syntax error on token\n"
                + "Error line 20: Type mismatch\n"
                + "Warning line 3: Unused import\n"
                + "Info: (no message)\n", text);
    }

    @Test
    @DisplayName("problem labels name only the non-zero severities")
    void problemsLabel() {
        assertEquals("2 errors, 1 warning", ContextItems.problemsLabel(List.of(
                new ContextItems.Problem(2, 1, "a"), new ContextItems.Problem(2, 2, "b"),
                new ContextItems.Problem(1, 3, "c"))));
        assertEquals("1 error", ContextItems.problemsLabel(List.of(new ContextItems.Problem(2, 1, "a"))));
        assertEquals("3 warnings, 1 info", ContextItems.problemsLabel(List.of(
                new ContextItems.Problem(1, 1, "a"), new ContextItems.Problem(1, 2, "b"),
                new ContextItems.Problem(1, 3, "c"), new ContextItems.Problem(0, 4, "d"))));
        assertEquals("0 problems", ContextItems.problemsLabel(List.of()));
    }

    @Test
    @DisplayName("kinds: supported kept in order and de-duplicated; valid-but-unsupported reported; garbage dropped")
    void knownKinds() {
        List<String> unsupported = new ArrayList<>();
        List<String> kinds = ContextItems.knownKinds(
                Arrays.asList("diff", "page", "file", "diff", "bogus", 42, null, "selection", "GITLOG",
                        "pageelements", "terminal", "page"),
                unsupported);
        assertEquals(List.of("diff", "file", "selection", "gitlog"), kinds);
        // Echoed in the protocol's own spelling, whatever case arrived.
        assertEquals(List.of("page", "pageElements", "terminal"), unsupported);
        assertEquals(List.of(), ContextItems.knownKinds("not-a-list", new ArrayList<>()));
    }

    @Test
    @DisplayName("init.capabilities: five context kinds, reveal + privateMode on, knowledge as told")
    void capabilities() {
        Map<String, Object> caps = ContextItems.hostCapabilities(true);
        assertEquals(List.of("file", "selection", "diff", "diagnostics", "gitlog"), caps.get("context"));
        assertEquals(Boolean.TRUE, caps.get("insert"));
        assertEquals(Boolean.TRUE, caps.get("newFile"));
        assertEquals(Boolean.TRUE, caps.get("runCode"));
        assertEquals(Boolean.FALSE, caps.get("commitMessage"));
        assertEquals("{\"context\":[\"file\",\"selection\",\"diff\",\"diagnostics\",\"gitlog\"],\"insert\":true,"
                + "\"newFile\":true,\"runCode\":true,\"commitMessage\":false,\"reveal\":true,\"knowledge\":true,"
                + "\"privateMode\":true}", Json.write(caps));
        assertEquals(Boolean.FALSE, ContextItems.hostCapabilities(false).get("knowledge"));
    }
}
