// Editor context-menu actions: take the selection, open the VegaDuta view, and
// stage the text into the chat composer as a selection.context message - the
// same message VS Code and JetBrains send, so the shared chat app handles it
// with no host-specific code.
//
// Nothing is SENT automatically: the composer is prefilled and the user
// presses Send. That is the behaviour of the other clients and it is the right
// one - an editor right-click must not spend tokens on its own.

package ai.vegaduta.ide.eclipse.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.ui.EditorBridge;
import ai.vegaduta.ide.eclipse.ui.VegadutaView;

public abstract class SelectionToChatHandler extends AbstractHandler {

    private final String instruction;

    protected SelectionToChatHandler(String instruction) {
        this.instruction = instruction;
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        EditorBridge.Selection selection = EditorBridge.currentSelection();
        if (selection == null) {
            // Enablement already hides the action without a text selection;
            // this covers the keyboard-binding path, where it can still fire.
            VegadutaPlugin.logInfo("VegaDuta editor action ignored - nothing selected");
            return null;
        }
        VegadutaView view = showView();
        if (view == null) {
            return null;
        }
        // Single newline, matching clients/vscode/src/commands/selection.ts -
        // the instruction line ends up INSIDE the fenced block the webview
        // builds, because the frozen protocol's selection.context has no
        // separate instruction field.
        view.sendSelection(instruction + "\n" + selection.text(),
                selection.languageId(), selection.fileName());
        return null;
    }

    /** Opens (or focuses) the VegaDuta view and returns it. The view has to
     * exist before the message is posted - the bridge queues posts until the
     * page says "ready", but there is no bridge at all until the part is
     * created. */
    static VegadutaView showView() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }
        try {
            // showView returns the part; the active() accessor is still used,
            // because that is what reports a view whose bridge is up.
            page.showView(VegadutaView.VIEW_ID);
        } catch (PartInitException e) {
            VegadutaPlugin.logError("Could not open the VegaDuta view", e);
            return null;
        }
        return VegadutaView.active();
    }

    /** The three instruction strings are copied VERBATIM from
     * clients/vscode/src/commands/selection.ts's INSTRUCTIONS map, which is the
     * only client that has all three. They are not reworded here: the same
     * action should produce the same prompt whichever editor the user is in.
     * VS Code's "tests" and "docs" instructions are in PrefillHandler, which
     * stages them with ui.prefill and a selection attachment instead. */
    public static class Explain extends SelectionToChatHandler {
        public Explain() {
            super("Explain what this code does and point out any pitfalls.");
        }
    }

    public static class Fix extends SelectionToChatHandler {
        public Fix() {
            super("Find and fix the bugs in this code. Reply with the corrected code and a short note"
                    + " on what changed.");
        }
    }

    public static class Refactor extends SelectionToChatHandler {
        public Refactor() {
            super("Refactor this code for clarity and maintainability without changing behavior."
                    + " Reply with the refactored code.");
        }
    }
}
