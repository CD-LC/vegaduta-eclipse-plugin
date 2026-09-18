// Turns a context.request into a context.result's items + missing lists.
//
// Two halves, split by thread:
//  - ui/EditorContext reads the workbench ON THE UI THREAD into an immutable
//    EditorSnapshot (cheap: document text, selection, marker attributes,
//    paths). No process, no disk walk.
//  - this class runs on the bridge's BACKGROUND executor: finds the git root,
//    runs git (blocking, bounded by GitDiff's timeout), truncates and labels.
//
// Pure apart from the injected diff runner, so every rule about what reaches
// the model - and every "missing" reason - is unit-tested.

package ai.vegaduta.ide.eclipse.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class ContextAssembler {

    private ContextAssembler() {
    }

    /**
     * What the UI thread saw. Any field may be null.
     *
     * @param editorOpen       a text editor is the active editor
     * @param fileLabel        workspace-relative path ("project/src/App.java"),
     *                         or the editor's name for a file outside the workspace
     * @param fileText         the editor's whole document (unsaved edits included)
     * @param languageId       protocol languageId for the file, or null
     * @param selectionText    the current text selection, or null/blank
     * @param fileLocation     the file on disk, when it has one
     * @param projectLocations open workspace projects on disk - the diff's
     *                         fallback when no editor is open
     * @param problems         IMarker.PROBLEM markers on the file; null when
     *                         they could not be read (see problemsUnavailable)
     * @param problemsUnavailable reason problems is null
     */
    public record EditorSnapshot(boolean editorOpen, String fileLabel, String fileText, String languageId,
            String selectionText, Path fileLocation, List<Path> projectLocations,
            List<ContextItems.Problem> problems, String problemsUnavailable) {
    }

    public record Assembled(List<Map<String, Object>> items, List<Map<String, Object>> missing) {
    }

    static final String NO_EDITOR = "No text editor is active - click into the file you mean, then try again.";

    /** The git log runner defaults to the real one; tests that do not
     * request "gitlog" never reach it. */
    public static Assembled assemble(List<String> kinds, List<String> unsupported, EditorSnapshot snapshot,
            Function<Path, GitDiff.Result> diffRunner) {
        return assemble(kinds, unsupported, snapshot, diffRunner, GitLog::run);
    }

    public static Assembled assemble(List<String> kinds, List<String> unsupported, EditorSnapshot snapshot,
            Function<Path, GitDiff.Result> diffRunner, Function<Path, GitLog.Result> logRunner) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        for (String kind : kinds) {
            switch (kind) {
                case "file" -> file(snapshot, items, missing);
                case "selection" -> selection(snapshot, items, missing);
                case "diff" -> diff(snapshot, diffRunner, items, missing);
                case "diagnostics" -> diagnostics(snapshot, items, missing);
                case "gitlog" -> gitlog(snapshot, logRunner, items, missing);
                default -> missing.add(ContextItems.missing(kind, "Not available in Eclipse."));
            }
        }
        if (unsupported != null) {
            for (String kind : unsupported) {
                missing.add(ContextItems.missing(kind, "Not available in Eclipse."));
            }
        }
        return new Assembled(items, missing);
    }

    private static void file(EditorSnapshot s, List<Map<String, Object>> items, List<Map<String, Object>> missing) {
        if (s == null || !s.editorOpen()) {
            missing.add(ContextItems.missing("file", NO_EDITOR));
        } else if (s.fileText() == null) {
            missing.add(ContextItems.missing("file", "The active editor's text could not be read."));
        } else {
            items.add(ContextItems.item("file", labelOr(s.fileLabel(), "Active file"), s.fileText(),
                    s.languageId()));
        }
    }

    private static void selection(EditorSnapshot s, List<Map<String, Object>> items,
            List<Map<String, Object>> missing) {
        if (s == null || !s.editorOpen()) {
            missing.add(ContextItems.missing("selection", NO_EDITOR));
        } else if (s.selectionText() == null || s.selectionText().isBlank()) {
            missing.add(ContextItems.missing("selection", "Nothing is selected in the active editor."));
        } else {
            items.add(ContextItems.item("selection", ContextItems.selectionLabel(s.selectionText()),
                    s.selectionText(), s.languageId()));
        }
    }

    private static void diagnostics(EditorSnapshot s, List<Map<String, Object>> items,
            List<Map<String, Object>> missing) {
        if (s == null || !s.editorOpen()) {
            missing.add(ContextItems.missing("diagnostics", NO_EDITOR));
        } else if (s.problems() == null) {
            missing.add(ContextItems.missing("diagnostics",
                    labelOr(s.problemsUnavailable(), "Problems could not be read for this file.")));
        } else if (s.problems().isEmpty()) {
            missing.add(ContextItems.missing("diagnostics",
                    "Eclipse reports no problems in " + labelOr(s.fileLabel(), "the active file") + "."));
        } else {
            String label = labelOr(s.fileLabel(), "the active file");
            items.add(ContextItems.item("diagnostics", ContextItems.problemsLabel(s.problems()),
                    ContextItems.formatProblems(label, s.problems()), null));
        }
    }

    /**
     * The repository a git-backed kind reads: the one holding the active file,
     * or - with no local file to anchor to - the one repository every open
     * project agrees on. Returns null after adding a "missing" entry for
     * {@code kind} when there is no single answer. Guessing between several
     * repositories would review (or imitate) the wrong code.
     */
    static Path resolveRepository(EditorSnapshot s, String kind, List<Map<String, Object>> missing) {
        if (s != null && s.fileLocation() != null) {
            Path root = GitDiff.findGitRoot(s.fileLocation());
            if (root == null) {
                missing.add(ContextItems.missing(kind,
                        labelOr(s.fileLabel(), "The active file") + " is not inside a git repository."));
            }
            return root;
        }
        Set<Path> roots = new LinkedHashSet<>();
        if (s != null && s.projectLocations() != null) {
            for (Path project : s.projectLocations()) {
                Path found = GitDiff.findGitRoot(project);
                if (found != null) {
                    roots.add(found);
                }
            }
        }
        if (roots.size() == 1) {
            return roots.iterator().next();
        }
        missing.add(ContextItems.missing(kind, roots.isEmpty()
                ? "No open project is inside a git repository."
                : "Several git repositories are open - open a file from the one you mean, then try again."));
        return null;
    }

    private static String repoName(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    private static void gitlog(EditorSnapshot s, Function<Path, GitLog.Result> runner,
            List<Map<String, Object>> items, List<Map<String, Object>> missing) {
        Path root = resolveRepository(s, "gitlog", missing);
        if (root == null) {
            return;
        }
        GitLog.Result result = runner.apply(root);
        if (!result.ok()) {
            missing.add(ContextItems.missing("gitlog", result.failure()));
            return;
        }
        List<GitLog.Commit> commits = GitLog.parse(result.output());
        if (commits.isEmpty()) {
            missing.add(ContextItems.missing("gitlog", "This repository has no commits yet."));
            return;
        }
        GitLog.Rendered rendered = GitLog.render(commits, repoName(root));
        Map<String, Object> item = ContextItems.item("gitlog", GitLog.label(rendered), rendered.text(), null);
        if (rendered.truncated()) {
            item.put("truncated", Boolean.TRUE);
        }
        items.add(item);
    }

    private static void diff(EditorSnapshot s, Function<Path, GitDiff.Result> runner,
            List<Map<String, Object>> items, List<Map<String, Object>> missing) {
        Path root = resolveRepository(s, "diff", missing);
        if (root == null) {
            return;
        }
        GitDiff.Result result = runner.apply(root);
        if (!result.ok()) {
            missing.add(ContextItems.missing("diff", result.failure()));
            return;
        }
        if (result.diff() == null || result.diff().isBlank()) {
            missing.add(ContextItems.missing("diff", "No uncommitted changes to tracked files in "
                    + repoName(root) + "."));
            return;
        }
        Map<String, Object> item = ContextItems.item("diff", ContextItems.diffLabel(result.diff()),
                result.diff(), "diff");
        if (result.overflowed()) {
            // git wrote more than was kept, so the file count read from the
            // kept part is a lower bound - say so rather than under-report.
            int files = ContextItems.diffFileCount(result.diff());
            item.put("label", "Working tree diff (" + files + "+ files)");
            item.put("truncated", Boolean.TRUE);
        }
        items.add(item);
    }

    private static String labelOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
