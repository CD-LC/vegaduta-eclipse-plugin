// RevealPaths: the containment rules ui.reveal applies to an untrusted path
// before the workspace ever sees it, and the 1-based -> 0-based line mapping.

package ai.vegaduta.ide.eclipse.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RevealPathsTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("relative paths normalize: either separator, '.' and empty segments dropped")
    void normalizesRelative() {
        assertEquals(List.of("app", "src", "Main.java"), RevealPaths.normalizeRelative("app/src/Main.java").segments());
        assertEquals("app/src/Main.java", RevealPaths.normalizeRelative("app\\src\\Main.java").joined());
        assertEquals("app/src/Main.java", RevealPaths.normalizeRelative("./app//src/./Main.java").joined());
        assertEquals("Main.java", RevealPaths.normalizeRelative("  Main.java ").joined());
    }

    @Test
    @DisplayName("any '..' segment is refused, even one that would stay inside")
    void refusesClimbing() {
        for (String path : List.of("../secret.txt", "app/../../etc/passwd", "app/../app/Main.java", "..",
                "app\\..\\..\\Windows\\win.ini", "a/b/..")) {
            RevealPaths.Normalized n = RevealPaths.normalizeRelative(path);
            assertFalse(n.ok(), path);
            assertTrue(n.error().contains(".."), path + " -> " + n.error());
            assertTrue(n.segments().isEmpty());
        }
        // A name that merely CONTAINS dots is a normal file.
        assertTrue(RevealPaths.normalizeRelative("app/..hidden/x..y").ok());
    }

    @Test
    @DisplayName("absolute paths, drive letters, URIs, streams and NUL are refused as relative paths")
    void refusesAbsoluteAndOddForms() {
        for (String path : List.of("/etc/passwd", "\\Windows\\win.ini", "C:\\Windows\\win.ini", "c:/x.txt",
                "\\\\server\\share\\x", "C:relative.txt", "file:///etc/passwd", "a.txt:stream", "a\0b",
                "", "   ", "/", "./.")) {
            assertFalse(RevealPaths.normalizeRelative(path).ok(), "should refuse: " + path);
        }
        assertFalse(RevealPaths.normalizeRelative(null).ok());
        assertFalse(RevealPaths.normalizeRelative("a/".repeat(600)).ok(), "over-long path");
    }

    @Test
    @DisplayName("isAbsolute recognises POSIX, Windows drive and UNC forms")
    void isAbsolute() {
        assertTrue(RevealPaths.isAbsolute("/x"));
        assertTrue(RevealPaths.isAbsolute("\\x"));
        assertTrue(RevealPaths.isAbsolute("C:\\x"));
        assertTrue(RevealPaths.isAbsolute("d:/x"));
        assertTrue(RevealPaths.isAbsolute("\\\\server\\share"));
        assertFalse(RevealPaths.isAbsolute("src/x"));
        assertFalse(RevealPaths.isAbsolute("1:x"));
        assertFalse(RevealPaths.isAbsolute(null));
    }

    @Test
    @DisplayName("an absolute path is accepted only inside a base, segment-wise")
    void relativeToBase() {
        Path project = temp.resolve("work").resolve("app");
        String inside = project.resolve("src").resolve("Main.java").toString();
        assertEquals("src/Main.java", RevealPaths.relativeToBase(inside, List.of(project)));

        // A sibling whose name merely starts with the project's is outside.
        String sibling = temp.resolve("work").resolve("app-secrets").resolve("key.pem").toString();
        assertNull(RevealPaths.relativeToBase(sibling, List.of(project)));

        // Climbing out through the base is normalized away BEFORE the check.
        String escape = project.toString() + java.io.File.separator + ".." + java.io.File.separator
                + "app-secrets" + java.io.File.separator + "key.pem";
        assertNull(RevealPaths.relativeToBase(escape, List.of(project)));

        // The base itself is not a file to open.
        assertNull(RevealPaths.relativeToBase(project.toString(), List.of(project)));

        // First containing base wins; unrelated bases are skipped.
        Path other = temp.resolve("other");
        assertEquals("src/Main.java", RevealPaths.relativeToBase(inside, List.of(other, project)));
        assertNull(RevealPaths.relativeToBase(inside, List.of()));
        assertNull(RevealPaths.relativeToBase(null, List.of(project)));
    }

    @Test
    @DisplayName("lines: 1-based inclusive in, 0-based out; end clamped; reversed range collapsed")
    void lines() {
        assertEquals(new RevealPaths.LineRange(0, 0, false), RevealPaths.lines(1, 1, 10));
        assertEquals(new RevealPaths.LineRange(4, 9, false), RevealPaths.lines(5, 10, 10));
        assertEquals(new RevealPaths.LineRange(4, 9, true), RevealPaths.lines(5, 99, 10));
        assertEquals(new RevealPaths.LineRange(6, 6, true), RevealPaths.lines(7, 3, 10));
        assertNull(RevealPaths.lines(0, 3, 10), "line 0 does not exist");
        assertNull(RevealPaths.lines(-4, 3, 10));
        assertNull(RevealPaths.lines(11, 12, 10), "past the end of the document");
        assertNull(RevealPaths.lines(1, 1, 0));
    }
}
