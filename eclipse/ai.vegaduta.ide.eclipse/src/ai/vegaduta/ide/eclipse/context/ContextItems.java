// Pure helpers for the context.request / context.result half of
// clients/shared/src/webview/protocol.ts: truncation, chip labels, and the
// plain-text rendering of editor problems.
//
// No SWT, no workbench, no OSGi - EditorContext (the workbench half) collects
// raw values on the UI thread and hands them here, so everything that decides
// WHAT the model sees is unit-tested directly.

package ai.vegaduta.ide.eclipse.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import ai.vegaduta.ide.eclipse.json.Json;

public final class ContextItems {

    private ContextItems() {
    }

    /** Mirror of CONTEXT_ITEM_MAX_CHARS in protocol.ts. Java's String.length()
     * counts UTF-16 code units, which is exactly what a JS string's length
     * counts, so the two hosts agree on the same number. */
    public static final int MAX_CHARS = 24_000;

    /** The context kinds this host supplies (init.capabilities.context).
     * "page" and "pageElements" are Chrome's; "terminal" needs a terminal
     * selection API that stock Eclipse does not expose to other bundles. */
    public static final List<String> SUPPORTED_KINDS = List.of("file", "selection", "diff", "diagnostics",
            "gitlog");

    /** Every ContextKind protocol.ts defines, in its own spelling. A kind the
     * webview sends that is here but not in SUPPORTED_KINDS is answered as
     * missing; anything else is protocol garbage and dropped. */
    public static final List<String> ALL_KINDS = List.of("file", "selection", "diff", "diagnostics", "page",
            "gitlog", "terminal", "pageElements");

    /**
     * What this host really services (protocol.ts HostCapabilities):
     *  - context: file, selection, diff and gitlog (git on PATH), diagnostics
     *    (problem markers). "page"/"pageElements" are Chrome's; "terminal" is
     *    not offered (see SUPPORTED_KINDS).
     *  - insert: ui.insert writes into the active text editor.
     *  - newFile: ui.newFile opens a scratch file in an editor.
     *  - runCode: sdlc.run is handled (WebviewBridge.handleSdlcRun).
     *  - commitMessage: false. Eclipse's commit box is EGit's, EGit is an
     *    optional install, and this bundle takes no dependency on it.
     *  - reveal: ui.reveal opens a workspace file and selects a line range.
     *  - knowledge: knowledge.search is serviced over the JWT surface. False
     *    when the only credential is a vmcp_ API key, whose /api/dev/v1
     *    surface has no knowledge endpoints. When nobody is signed in it is
     *    true: a sign-in can follow without a page reload, and until then the
     *    host answers "signed-out".
     *  - privateMode: this host blocks its own hosted paths while Private
     *    Mode is on (WebviewBridge, PrivacyGate).
     */
    public static Map<String, Object> hostCapabilities(boolean knowledge) {
        Map<String, Object> capabilities = Json.object();
        capabilities.put("context", new ArrayList<>(SUPPORTED_KINDS));
        capabilities.put("insert", Boolean.TRUE);
        capabilities.put("newFile", Boolean.TRUE);
        capabilities.put("runCode", Boolean.TRUE);
        capabilities.put("commitMessage", Boolean.FALSE);
        capabilities.put("reveal", Boolean.TRUE);
        capabilities.put("knowledge", knowledge);
        capabilities.put("privateMode", Boolean.TRUE);
        return capabilities;
    }

    /** Result of {@link #truncate}. */
    public record Truncated(String text, boolean truncated) {
    }

    /**
     * Cuts {@code text} to at most {@code max} UTF-16 units. Never splits a
     * surrogate pair: if the cut would land between a high and a low surrogate
     * the high one is dropped too, so the result is always valid UTF-16 (a lone
     * surrogate would reach the model as U+FFFD, or worse, break the JSON).
     */
    public static Truncated truncate(String text, int max) {
        if (text == null) {
            return new Truncated("", false);
        }
        if (text.length() <= max) {
            return new Truncated(text, false);
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return new Truncated(text.substring(0, end), true);
    }

    public static Truncated truncate(String text) {
        return truncate(text, MAX_CHARS);
    }

    /** Builds one ContextItem map, truncating {@code text} on the way. */
    public static Map<String, Object> item(String kind, String label, String text, String languageId) {
        Truncated cut = truncate(text);
        Map<String, Object> item = Json.object();
        item.put("kind", kind);
        item.put("label", label);
        item.put("text", cut.text());
        item.put("languageId", languageId);
        if (cut.truncated()) {
            item.put("truncated", Boolean.TRUE);
        }
        return item;
    }

    /** One entry of context.result's {@code missing} array. */
    public static Map<String, Object> missing(String kind, String reason) {
        Map<String, Object> entry = Json.object();
        entry.put("kind", kind);
        entry.put("reason", reason);
        return entry;
    }

    /** Number of lines in a selection as a person would count them: a
     * trailing newline does not start a new line. */
    public static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i < text.length() - 1) {
                lines++;
            }
        }
        return lines;
    }

    /** "Selection (12 lines)" / "Selection (1 line)". */
    public static String selectionLabel(String text) {
        int lines = lineCount(text);
        return "Selection (" + lines + (lines == 1 ? " line)" : " lines)");
    }

    /** Files touched by a unified git diff: one "diff --git" header each. */
    public static int diffFileCount(String diff) {
        if (diff == null || diff.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                count++;
            }
        }
        return count;
    }

    /** "Working tree diff (3 files)". */
    public static String diffLabel(String diff) {
        int files = diffFileCount(diff);
        return "Working tree diff (" + files + (files == 1 ? " file)" : " files)");
    }

    // --- diagnostics ---------------------------------------------------------

    /** Mirrors IMarker.SEVERITY_ERROR / _WARNING / _INFO (2 / 1 / 0), kept as
     * plain ints so this class does not need org.eclipse.core.resources. */
    public static final int SEVERITY_ERROR = 2;
    public static final int SEVERITY_WARNING = 1;
    public static final int SEVERITY_INFO = 0;

    /** One problem marker, already read off the IMarker. {@code line} is
     * 1-based, or <= 0 when the marker has no line. */
    public record Problem(int severity, int line, String message) {
    }

    private static String severityWord(int severity) {
        return switch (severity) {
            case SEVERITY_ERROR -> "Error";
            case SEVERITY_WARNING -> "Warning";
            default -> "Info";
        };
    }

    /**
     * Renders problems one per line, errors first, then by line:
     * {@code "Error line 12: Syntax error on token"}. Newlines inside a
     * message are flattened so one problem is always one line.
     */
    public static String formatProblems(String fileLabel, List<Problem> problems) {
        List<Problem> sorted = new ArrayList<>(problems);
        sorted.sort(Comparator.comparingInt(Problem::severity).reversed()
                .thenComparingInt(p -> p.line() <= 0 ? Integer.MAX_VALUE : p.line()));
        StringBuilder out = new StringBuilder();
        out.append("Problems reported by Eclipse for ").append(fileLabel).append(":\n");
        for (Problem problem : sorted) {
            out.append(severityWord(problem.severity()));
            if (problem.line() > 0) {
                out.append(" line ").append(problem.line());
            }
            String message = problem.message() == null ? "(no message)"
                    : problem.message().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').strip();
            out.append(": ").append(message.isEmpty() ? "(no message)" : message).append('\n');
        }
        return out.toString();
    }

    /** "2 errors, 1 warning, 1 info" - only the non-zero parts. */
    public static String problemsLabel(List<Problem> problems) {
        int errors = 0;
        int warnings = 0;
        for (Problem problem : problems) {
            if (problem.severity() == SEVERITY_ERROR) {
                errors++;
            } else if (problem.severity() == SEVERITY_WARNING) {
                warnings++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (errors > 0) {
            parts.add(errors + (errors == 1 ? " error" : " errors"));
        }
        if (warnings > 0) {
            parts.add(warnings + (warnings == 1 ? " warning" : " warnings"));
        }
        int other = problems.size() - errors - warnings;
        if (other > 0) {
            parts.add(other + " info");
        }
        return parts.isEmpty() ? "0 problems" : String.join(", ", parts);
    }

    /** Normalizes a kinds array from the webview: supported kinds only, no
     * duplicates, order kept, matched case-insensitively but returned in the
     * protocol's own spelling. A valid-but-unsupported kind ("page",
     * "terminal", "pageElements") goes to {@code unknownOut} so the caller
     * reports it as missing. */
    public static List<String> knownKinds(Object raw, List<String> unknownOut) {
        List<String> kinds = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof String kind)) {
                    continue;
                }
                String canonical = canonicalKind(kind);
                if (canonical == null) {
                    // Not a ContextKind at all: echoing it back would put a
                    // non-ContextKind in context.result.
                    continue;
                }
                if (SUPPORTED_KINDS.contains(canonical)) {
                    if (!kinds.contains(canonical)) {
                        kinds.add(canonical);
                    }
                } else if (unknownOut != null && !unknownOut.contains(canonical)) {
                    unknownOut.add(canonical);
                }
            }
        }
        return kinds;
    }

    private static String canonicalKind(String kind) {
        for (String known : ALL_KINDS) {
            if (known.equalsIgnoreCase(kind)) {
                return known;
            }
        }
        return null;
    }
}
