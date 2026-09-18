// Commands that stage a prompt with ui.prefill: the instruction goes into the
// chat composer and the chat app requests the listed context kinds from this
// host (context.request -> WebviewBridge) and attaches them as chips.
//
// Nothing is sent automatically (send=false), for the same reason as the
// Explain/Fix/Refactor actions: a menu click must not spend tokens on its own.
// The person sees the prompt and exactly what is attached, then presses Send.

package ai.vegaduta.ide.eclipse.actions;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.ui.EditorBridge;
import ai.vegaduta.ide.eclipse.ui.VegadutaView;

public abstract class PrefillHandler extends AbstractHandler {

    private final String instruction;
    private final List<String> contextKinds;
    private final boolean needsSelection;

    protected PrefillHandler(String instruction, List<String> contextKinds, boolean needsSelection) {
        this.instruction = instruction;
        this.contextKinds = contextKinds;
        this.needsSelection = needsSelection;
    }

    @Override
    public Object execute(ExecutionEvent event) {
        if (needsSelection && EditorBridge.currentSelection() == null) {
            // Enablement hides the menu item without a selection; this covers
            // a key binding firing a stale handler.
            VegadutaPlugin.logInfo("VegaDuta command ignored - nothing selected");
            return null;
        }
        VegadutaView view = SelectionToChatHandler.showView();
        if (view != null) {
            view.prefill(instruction, contextKinds, false);
        }
        return null;
    }

    /** Window > VegaDuta > Review My Changes: attaches `git diff HEAD` of the
     * repository holding the active file. */
    public static class ReviewChanges extends PrefillHandler {
        public ReviewChanges() {
            super("Review my uncommitted changes in the attached diff. List real problems first - bugs,"
                    + " unhandled edge cases, security issues, missing tests - each with the file and line it"
                    + " is in and a concrete fix. Skip style nitpicks. If nothing needs changing, say so.",
                    List.of("diff"), false);
        }
    }

    /** Instruction copied VERBATIM from clients/vscode/src/commands/selection.ts
     * ("tests"), so the same action produces the same prompt in every IDE. */
    public static class WriteTests extends PrefillHandler {
        public WriteTests() {
            super("Write tests for this code, covering the main behavior and realistic edge cases. Match"
                    + " whatever test framework/style is already evident in this file or project; if none is"
                    + " evident, pick the conventional default for the language. Reply with the test code.",
                    List.of("selection"), true);
        }
    }

    /** Instruction copied VERBATIM from clients/vscode/src/commands/selection.ts
     * ("docs"). */
    public static class AddDocs extends PrefillHandler {
        public AddDocs() {
            super("Add clear documentation to this code (docstrings/JSDoc/doc-comments as appropriate for the"
                    + " language). Cover non-obvious parameters, return values, thrown errors, and any important"
                    + " behavior - don't restate what's already obvious from the names. Reply with the fully"
                    + " documented code.",
                    List.of("selection"), true);
        }
    }
}
