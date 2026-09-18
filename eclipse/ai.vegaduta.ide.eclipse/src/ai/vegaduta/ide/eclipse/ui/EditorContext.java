// The workbench half of context.request: reads the active editor into an
// immutable ContextAssembler.EditorSnapshot. The other half (git, truncation,
// labels) runs on a background thread in ContextAssembler.
//
// UI-thread rule: every read of editor/selection/marker state happens inside
// snapshot(), which runs the collection directly when already on the SWT UI
// thread (the BrowserFunction callback is) and otherwise via Display.syncExec.
// Collection is cheap - document text, one selection, one marker query, a few
// paths - and does no process or network work, so the UI thread is never
// held for long. git runs later, off the UI thread.

package ai.vegaduta.ide.eclipse.ui;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.context.ContextAssembler.EditorSnapshot;
import ai.vegaduta.ide.eclipse.context.ContextItems;

public final class EditorContext {

    private EditorContext() {
    }

    /** Collects the snapshot on the UI thread, from any thread. Never throws:
     * a failure is logged and reported as "no editor". */
    public static EditorSnapshot snapshot() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return empty();
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            return empty();
        }
        if (Display.getCurrent() == display) {
            return collectSafely();
        }
        AtomicReference<EditorSnapshot> result = new AtomicReference<>(empty());
        display.syncExec(() -> result.set(collectSafely()));
        return result.get();
    }

    private static EditorSnapshot empty() {
        return new EditorSnapshot(false, null, null, null, null, null, List.of(), null, null);
    }

    private static EditorSnapshot collectSafely() {
        try {
            return collect();
        } catch (RuntimeException e) {
            VegadutaPlugin.logWarning("reading the active editor for chat context failed", e);
            return new EditorSnapshot(false, null, null, null, null, null, projectLocations(), null, null);
        }
    }

    private static EditorSnapshot collect() {
        List<Path> projects = projectLocations();
        ITextEditor editor = EditorBridge.activeTextEditor();
        if (editor == null) {
            return new EditorSnapshot(false, null, null, null, null, null, projects, null, null);
        }
        IEditorInput input = editor.getEditorInput();
        IFile file = (input == null) ? null : input.getAdapter(IFile.class);

        String label;
        Path location = null;
        if (file != null) {
            // Workspace-relative, project first: "my-app/src/Main.java".
            label = file.getFullPath().makeRelative().toString();
            IPath onDisk = file.getLocation();
            if (onDisk != null) {
                location = onDisk.toFile().toPath();
            }
        } else {
            label = (input == null) ? null : input.getName();
            if (input instanceof IURIEditorInput uriInput) {
                location = localPath(uriInput.getURI());
            }
        }

        String text = null;
        IDocumentProvider provider = editor.getDocumentProvider();
        IDocument document = (provider == null || input == null) ? null : provider.getDocument(input);
        if (document != null) {
            text = document.get();
        }

        String selection = null;
        if (editor.getSelectionProvider() != null
                && editor.getSelectionProvider().getSelection() instanceof ITextSelection textSelection) {
            selection = textSelection.getText();
        }

        List<ContextItems.Problem> problems = null;
        String problemsUnavailable = null;
        if (file == null) {
            problemsUnavailable = "Problems are only tracked for files inside the Eclipse workspace.";
        } else {
            try {
                IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
                problems = new ArrayList<>(markers.length);
                for (IMarker marker : markers) {
                    problems.add(new ContextItems.Problem(
                            marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO),
                            marker.getAttribute(IMarker.LINE_NUMBER, -1),
                            marker.getAttribute(IMarker.MESSAGE, "")));
                }
            } catch (CoreException e) {
                problemsUnavailable = "Eclipse could not read the problems for this file: " + e.getMessage();
            }
        }

        String nameForLanguage = (file != null) ? file.getName() : label;
        return new EditorSnapshot(true, label, text, LanguageIds.forFileName(nameForLanguage), selection,
                location, projects, problems, problemsUnavailable);
    }

    private static Path localPath(URI uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        try {
            return Paths.get(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Path> projectLocations() {
        List<Path> out = new ArrayList<>();
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen() && project.getLocation() != null) {
                    out.add(project.getLocation().toFile().toPath());
                }
            }
        } catch (RuntimeException e) {
            // No workspace (IllegalStateException in odd launch configs): the
            // diff fallback simply has nothing to offer.
        }
        return out;
    }
}
