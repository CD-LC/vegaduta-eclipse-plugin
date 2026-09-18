// Pure half of ui.reveal (Code Tour): turning the path and line numbers the
// webview sends into something safe to hand to the workspace.
//
// The path comes from model output relayed by the page, so it is untrusted.
// Rules, all unit-tested (RevealPathsTest):
//  - relative paths only, "/" or "\" separators, "." segments ignored;
//  - ANY ".." segment is refused outright, even one that would stay inside
//    (a/../b) - there is no legitimate reason for a tour stop to climb, and a
//    rule with no exceptions cannot be talked around;
//  - an absolute path is accepted only when it lies inside one of the given
//    base directories (the open projects), and is then made relative to it;
//  - no NUL, no drive letters or URI schemes in a relative path.
// The workbench half (EditorBridge.reveal) then resolves the result against
// the workspace, which is its own containment boundary.
//
// Lines: 1-based inclusive on the wire; returned 0-based for IDocument, with
// the end clamped to the document and a reversed range collapsed to its start.

package ai.vegaduta.ide.eclipse.context;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class RevealPaths {

    private RevealPaths() {
    }

    /** Longest path accepted - a file path, not a document. */
    static final int MAX_PATH_CHARS = 1_024;

    /** Either the normalized segments or the reason they were refused. */
    public record Normalized(List<String> segments, String error) {
        static Normalized ok(List<String> segments) {
            return new Normalized(List.copyOf(segments), null);
        }

        static Normalized refused(String error) {
            return new Normalized(List.of(), error);
        }

        public boolean ok() {
            return error == null;
        }

        /** "project/src/Main.java". */
        public String joined() {
            return String.join("/", segments);
        }
    }

    /** True for "/x", "\\x", "C:\\x", "C:/x" and "\\\\server\\share". */
    public static boolean isAbsolute(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        char first = raw.charAt(0);
        if (first == '/' || first == '\\') {
            return true;
        }
        return raw.length() >= 2 && raw.charAt(1) == ':' && Character.isLetter(first);
    }

    /** Normalizes a RELATIVE path into segments, refusing anything that could
     * escape its base. See the header for the rules. */
    public static Normalized normalizeRelative(String raw) {
        if (raw == null || raw.isBlank()) {
            return Normalized.refused("No file path was given.");
        }
        if (raw.length() > MAX_PATH_CHARS) {
            return Normalized.refused("The file path is too long.");
        }
        if (raw.indexOf('\0') >= 0) {
            return Normalized.refused("The file path contains a NUL character.");
        }
        String path = raw.strip();
        if (isAbsolute(path)) {
            return Normalized.refused("Absolute paths outside the open projects are not opened.");
        }
        if (path.contains(":")) {
            // A drive-relative "C:foo", a URI "file:..." or an NTFS stream
            // "a.txt:secret" - none is a workspace path.
            return Normalized.refused("\"" + path + "\" is not a workspace path.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return Normalized.refused("Paths that climb out with \"..\" are not opened.");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return Normalized.refused("No file path was given.");
        }
        return Normalized.ok(segments);
    }

    /**
     * An absolute path made relative to the first base that CONTAINS it after
     * normalization, or null when none does. "Contains" is Path.startsWith on
     * normalized absolute paths - segment-wise, so /work/app does not contain
     * /work/app-secrets.
     *
     * @return the relative segments joined with "/", or null
     */
    public static String relativeToBase(String absolute, List<Path> bases) {
        if (absolute == null || bases == null || absolute.indexOf('\0') >= 0) {
            return null;
        }
        Path candidate;
        try {
            candidate = Paths.get(absolute).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        for (Path base : bases) {
            if (base == null) {
                continue;
            }
            Path root = base.toAbsolutePath().normalize();
            if (candidate.startsWith(root) && !candidate.equals(root)) {
                List<String> parts = new ArrayList<>();
                for (Path part : root.relativize(candidate)) {
                    parts.add(part.toString());
                }
                return String.join("/", parts);
            }
        }
        return null;
    }

    /** A validated 0-based inclusive line range. */
    public record LineRange(int startLine, int endLine, boolean clamped) {
    }

    /**
     * Converts the wire's 1-based inclusive lines to IDocument's 0-based ones.
     *
     * @param lineCount the document's IDocument.getNumberOfLines() (>= 1)
     * @return null when {@code startLine} is not a real line number (< 1 or
     *         past the end of the document) - nothing sensible to select
     */
    public static LineRange lines(long startLine, long endLine, int lineCount) {
        if (startLine < 1 || lineCount < 1 || startLine > lineCount) {
            return null;
        }
        long end = endLine < startLine ? startLine : endLine;
        boolean clamped = end != endLine;
        if (end > lineCount) {
            end = lineCount;
            clamped = true;
        }
        return new LineRange((int) startLine - 1, (int) end - 1, clamped);
    }
}
