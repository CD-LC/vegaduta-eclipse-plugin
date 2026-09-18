// Window > VegaDuta > Sign In. Starts the device flow; the browser opens by
// itself and the view's auth listener updates the chat UI when tokens land.

package ai.vegaduta.ide.eclipse.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.PlatformUI;

import ai.vegaduta.ide.eclipse.auth.DeviceFlowLogin;

public class SignInHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        DeviceFlowLogin.signIn(reason -> {
            if (reason == null) {
                return; // TokenStore's listener refreshes the view
            }
            PlatformUI.getWorkbench().getDisplay().asyncExec(() -> MessageDialog.openError(
                    PlatformUI.getWorkbench().getDisplay().getActiveShell(),
                    "VegaDuta sign-in failed",
                    reason));
        });
        return null;
    }
}
