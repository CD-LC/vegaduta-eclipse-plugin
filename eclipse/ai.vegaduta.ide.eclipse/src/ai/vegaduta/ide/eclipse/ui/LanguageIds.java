// File name -> languageId, in the vocabulary the shared protocol uses (the
// VS Code languageId names; Models.toSandboxLanguage consumes the same set).
//
// Split out of EditorBridge so it carries no SWT or workbench dependency and
// can be unit-tested directly.

package ai.vegaduta.ide.eclipse.ui;

import java.util.Locale;

public final class LanguageIds {

    private LanguageIds() {
    }

    /**
     * Returns null when the extension is not one we can name confidently.
     * That is deliberate: a WRONG languageId would route a fenced code block
     * to the wrong sandbox runtime, and the protocol already has a clean
     * "untagged" path (the sdlc.run handler's "tag the code block" reply).
     */
    public static String forFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return switch (fileName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "java" -> "java";
            case "py" -> "python";
            case "js", "mjs", "cjs" -> "javascript";
            case "jsx" -> "javascriptreact";
            case "ts", "mts", "cts" -> "typescript";
            case "tsx" -> "typescriptreact";
            case "kt", "kts" -> "kotlin";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "cs" -> "csharp";
            case "c", "h" -> "c";
            case "cpp", "cc", "cxx", "hpp" -> "cpp";
            case "sh", "bash" -> "shellscript";
            case "sql" -> "sql";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "md" -> "markdown";
            default -> null;
        };
    }

    /**
     * The file extension (no dot) for a languageId - the inverse of
     * {@link #forFileName}, used to name the scratch file ui.newFile opens so
     * Eclipse picks the matching editor. Falls back to "txt": an unknown or
     * absent languageId must still open, just as plain text.
     */
    public static String extensionFor(String languageId) {
        if (languageId == null) {
            return "txt";
        }
        return switch (languageId.toLowerCase(Locale.ROOT)) {
            case "java" -> "java";
            case "python" -> "py";
            case "javascript" -> "js";
            case "javascriptreact" -> "jsx";
            case "typescript" -> "ts";
            case "typescriptreact" -> "tsx";
            case "kotlin" -> "kt";
            case "go" -> "go";
            case "rust" -> "rs";
            case "ruby" -> "rb";
            case "php" -> "php";
            case "csharp" -> "cs";
            case "c" -> "c";
            case "cpp" -> "cpp";
            case "shellscript", "bash", "sh" -> "sh";
            case "sql" -> "sql";
            case "xml" -> "xml";
            case "html" -> "html";
            case "css" -> "css";
            case "json" -> "json";
            case "yaml" -> "yaml";
            case "markdown" -> "md";
            case "diff" -> "diff";
            default -> "txt";
        };
    }
}
