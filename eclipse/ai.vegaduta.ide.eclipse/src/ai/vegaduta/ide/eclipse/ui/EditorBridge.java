// The editor-facing side effects the chat protocol asks for (ui.insert,
// ui.copy, ui.newFile) and the selection read the editor actions need.
//
// Everything here hops to the UI thread itself, so callers on the bridge's
// background executor do not have to think about it. Every operation is
// best-effort and logs rather than throwing: "no editor is open" is a normal
// state, not an error.

package ai.vegaduta.ide.eclipse.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.context.RevealPaths;

public final class EditorBridge {

    private EditorBridge() {
    }

    /** What an editor selection carries into the chat composer. */
    public record Selection(String text, String languageId, String fileName) {
    }

    private static void onUiThread(Runnable action) {
        Display display = PlatformUI.isWorkbenchRunning()
                ? PlatformUI.getWorkbench().getDisplay()
                : Display.getCurrent();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (Display.getCurrent() == display) {
            action.run();
        } else {
            display.asyncExec(action);
        }
    }

    static ITextEditor activeTextEditor() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }
        IEditorPart editor = page.getActiveEditor();
        if (editor instanceof ITextEditor textEditor) {
            return textEditor;
        }
        return (editor == null) ? null : editor.getAdapter(ITextEditor.class);
    }

    /**
     * Inserts text at the caret of the active text editor, replacing the
     * selection if there is one. When there is nowhere to insert - no text
     * editor, a read-only one, or a failed edit - the text goes to the
     * clipboard instead and {@code report} says so, so a click on Insert is
     * never silently lost.
     */
    public static void insertAtCaret(String text, Consumer<String> report) {
        if (text == null || text.isEmpty()) {
            return;
        }
        onUiThread(() -> {
            ITextEditor editor = activeTextEditor();
            if (editor == null) {
                copyToClipboard(text);
                report.accept("No text editor is open to insert into - copied to the clipboard instead.");
                return;
            }
            if (!editor.isEditable()) {
                copyToClipboard(text);
                report.accept("The active editor is read-only - copied to the clipboard instead.");
                return;
            }
            IDocumentProvider provider = editor.getDocumentProvider();
            IDocument document = (provider == null) ? null : provider.getDocument(editor.getEditorInput());
            if (document == null) {
                copyToClipboard(text);
                report.accept("Could not reach the active editor's text - copied to the clipboard instead.");
                return;
            }
            int offset = 0;
            int length = 0;
            if (editor.getSelectionProvider() != null
                    && editor.getSelectionProvider().getSelection() instanceof ITextSelection selection) {
                offset = Math.max(0, selection.getOffset());
                length = Math.max(0, selection.getLength());
            }
            try {
                document.replace(offset, length, text);
            } catch (BadLocationException e) {
                VegadutaPlugin.logWarning("ui.insert landed outside the document", e);
                copyToClipboard(text);
                report.accept("Could not insert at the caret - copied to the clipboard instead.");
            }
        });
    }

    /** Puts text on the system clipboard. */
    public static void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        onUiThread(() -> {
            Clipboard clipboard = new Clipboard(Display.getCurrent());
            try {
                clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            } finally {
                clipboard.dispose();
            }
        });
    }

    /**
     * The active editor's selection, or null when there is no text editor or
     * nothing is selected. UI-thread only - the editor actions run there.
     */
    public static Selection currentSelection() {
        ITextEditor editor = activeTextEditor();
        if (editor == null || editor.getSelectionProvider() == null) {
            return null;
        }
        if (!(editor.getSelectionProvider().getSelection() instanceof ITextSelection selection)) {
            return null;
        }
        String text = selection.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        IEditorInput input = editor.getEditorInput();
        String fileName = (input == null) ? null : input.getName();
        return new Selection(text, LanguageIds.forFileName(fileName), fileName);
    }

    /**
     * ui.newFile: writes {@code text} to a scratch file and opens it in the
     * editor Eclipse associates with its extension.
     *
     * Why a real file and not an "untitled" editor: stock Eclipse has no
     * public API for an untitled text editor (the one the IDE uses is internal
     * to org.eclipse.ui.editors), and depending on internals breaks across
     * releases. A file in the system temp directory, opened through
     * IDE.openEditorOnFileStore, works in every Eclipse IDE package with no
     * extra dependency; the person uses File > Save As to keep it in a project.
     *
     * The file is written on the CALLER's thread - call this from a background
     * thread - and only the open hops to the UI thread. On any failure the text
     * is copied to the clipboard instead and {@code report} says so: the
     * content is never silently lost.
     */
    public static void openInNewEditor(String text, String languageId, Consumer<String> report) {
        if (text == null) {
            return;
        }
        Path file;
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "vegaduta");
            Files.createDirectories(dir);
            file = Files.createTempFile(dir, "vegaduta-", "." + LanguageIds.extensionFor(languageId));
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            VegadutaPlugin.logWarning("ui.newFile could not write a scratch file", e);
            copyToClipboard(text);
            report.accept("Could not create a new file - the code was copied to the clipboard instead.");
            return;
        }
        Path written = file;
        onUiThread(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = (window == null) ? null : window.getActivePage();
            if (page == null) {
                copyToClipboard(text);
                report.accept("No workbench window to open a file in - the code was copied to the clipboard.");
                return;
            }
            try {
                IFileStore store = EFS.getLocalFileSystem().getStore(written.toUri());
                IDE.openEditorOnFileStore(page, store);
                report.accept("Opened " + written.getFileName() + " (a scratch file in the temp folder - use"
                        + " File > Save As to keep it).");
            } catch (PartInitException | RuntimeException e) {
                VegadutaPlugin.logWarning("ui.newFile could not open an editor", e);
                copyToClipboard(text);
                report.accept("Could not open a new editor - the code was copied to the clipboard instead.");
            }
        });
    }

    // --- ui.reveal (Code Tour) -----------------------------------------------

    /**
     * ui.reveal: opens {@code path} and selects lines startLine..endLine
     * (1-based, inclusive). No path = the active editor.
     *
     * The path is untrusted (it comes from model output via the page), so it
     * goes through RevealPaths first - no "..", no absolute path outside an
     * open project - and is then resolved ONLY against open workspace
     * projects: a path that is not a workspace file is not opened, whatever
     * is on disk. Every refusal is reported on the view's status line.
     * Hops to the UI thread itself.
     */
    public static void reveal(String path, long startLine, long endLine, Consumer<String> report) {
        onUiThread(() -> {
            try {
                revealOnUiThread(path, startLine, endLine, report);
            } catch (RuntimeException e) {
                VegadutaPlugin.logWarning("ui.reveal failed", e);
                report.accept("Could not show those lines - see the Error Log.");
            }
        });
    }

    private static void revealOnUiThread(String path, long startLine, long endLine, Consumer<String> report) {
        ITextEditor editor;
        String shown;
        if (path == null || path.isBlank()) {
            editor = activeTextEditor();
            if (editor == null) {
                report.accept("No text editor is active to show those lines in.");
                return;
            }
            IEditorInput input = editor.getEditorInput();
            shown = (input == null) ? "the active editor" : input.getName();
        } else {
            IFile file = resolveWorkspaceFile(path, report);
            if (file == null) {
                return;
            }
            shown = file.getFullPath().makeRelative().toString();
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = (window == null) ? null : window.getActivePage();
            if (page == null) {
                report.accept("No workbench window to open " + shown + " in.");
                return;
            }
            IEditorPart part;
            try {
                part = IDE.openEditor(page, file, true);
            } catch (PartInitException e) {
                VegadutaPlugin.logWarning("ui.reveal could not open " + shown, e);
                report.accept("Could not open " + shown + " - see the Error Log.");
                return;
            }
            editor = (part instanceof ITextEditor text) ? text
                    : (part == null ? null : part.getAdapter(ITextEditor.class));
            if (editor == null) {
                report.accept("Opened " + shown + ", but not in a text editor, so no lines were selected.");
                return;
            }
        }
        IDocumentProvider provider = editor.getDocumentProvider();
        IDocument document = (provider == null) ? null : provider.getDocument(editor.getEditorInput());
        if (document == null) {
            report.accept("Could not read " + shown + " to select lines in it.");
            return;
        }
        int lineCount = document.getNumberOfLines();
        RevealPaths.LineRange range = RevealPaths.lines(startLine, endLine, lineCount);
        if (range == null) {
            report.accept("Line " + startLine + " is not in " + shown + " (it has " + lineCount + " lines).");
            return;
        }
        try {
            int start = document.getLineOffset(range.startLine());
            IRegion last = document.getLineInformation(range.endLine());
            int end = last.getOffset() + last.getLength();
            editor.selectAndReveal(start, Math.max(0, end - start));
        } catch (BadLocationException e) {
            report.accept("Those lines are not in " + shown + ".");
            return;
        }
        if (range.clamped()) {
            report.accept("Showing " + shown + " lines " + (range.startLine() + 1) + "-" + (range.endLine() + 1)
                    + " (the requested range ran past the end of the file).");
        }
    }

    /** Resolves a ui.reveal path to an existing file in an OPEN project, or
     * reports why not and returns null. */
    private static IFile resolveWorkspaceFile(String raw, Consumer<String> report) {
        List<IProject> projects = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                projects.add(project);
            }
        }
        String trimmed = raw.strip();
        if (projects.isEmpty()) {
            report.accept("No project is open in the workspace, so " + trimmed + " cannot be opened.");
            return null;
        }
        if (RevealPaths.isAbsolute(trimmed)) {
            for (IProject project : projects) {
                if (project.getLocation() == null) {
                    continue;
                }
                String relative = RevealPaths.relativeToBase(trimmed,
                        List.of(project.getLocation().toFile().toPath()));
                if (relative == null) {
                    continue;
                }
                RevealPaths.Normalized normalized = RevealPaths.normalizeRelative(relative);
                if (normalized.ok()) {
                    IFile file = project.getFile(
                            org.eclipse.core.runtime.Path.fromPortableString(normalized.joined()));
                    if (file.exists()) {
                        return file;
                    }
                }
            }
            report.accept(trimmed + " is not a file in an open project, so it was not opened.");
            return null;
        }
        RevealPaths.Normalized normalized = RevealPaths.normalizeRelative(trimmed);
        if (!normalized.ok()) {
            report.accept(normalized.error());
            return null;
        }
        List<String> segments = normalized.segments();
        // 1. "project/src/Main.java" - the form this host labels files with.
        if (segments.size() >= 2) {
            for (IProject project : projects) {
                if (project.getName().equals(segments.get(0))) {
                    IFile file = project.getFile(org.eclipse.core.runtime.Path
                            .fromPortableString(String.join("/", segments.subList(1, segments.size()))));
                    if (file.exists()) {
                        return file;
                    }
                }
            }
        }
        // 2. "src/Main.java" - relative to a project; only when exactly one
        //    open project has it, never a guess between several.
        List<IFile> matches = new ArrayList<>();
        for (IProject project : projects) {
            IFile file = project.getFile(org.eclipse.core.runtime.Path.fromPortableString(normalized.joined()));
            if (file.exists()) {
                matches.add(file);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        report.accept(matches.isEmpty()
                ? normalized.joined() + " is not a file in an open project."
                : normalized.joined() + " exists in several open projects - prefix it with the project name.");
        return null;
    }
}
