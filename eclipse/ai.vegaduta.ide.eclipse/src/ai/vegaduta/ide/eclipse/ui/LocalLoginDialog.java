// Identifier + password prompt for a personal install's local account
// (app.auth.mode=local - the install's own sign-in page posts the same two
// fields to /api/auth/local/login). One dialog, both fields: asking for the
// password in a second step twice is friction nobody needs for a server on
// their own machine.

package ai.vegaduta.ide.eclipse.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public final class LocalLoginDialog extends Dialog {

    private final String apiBase;

    private Text identifierField;
    private Text passwordField;
    private String identifier;
    private String password;

    public LocalLoginDialog(Shell parentShell, String apiBase) {
        super(parentShell);
        this.apiBase = apiBase;
    }

    /** The email the user typed; null until the dialog closed with OK. */
    public String identifier() {
        return identifier;
    }

    /** The password the user typed; null until the dialog closed with OK. */
    public String password() {
        return password;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("VegaDuta on this machine");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite grid = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).hint(320, SWT.DEFAULT).applyTo(grid);
        grid.setLayout(new GridLayout(2, false));

        Label prompt = new Label(grid, SWT.NONE);
        prompt.setText("Sign in to " + apiBase);
        GridDataFactory.swtDefaults().span(2, 1).applyTo(prompt);

        new Label(grid, SWT.NONE).setText("Email");
        identifierField = new Text(grid, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(identifierField);

        new Label(grid, SWT.NONE).setText("Password");
        passwordField = new Text(grid, SWT.BORDER | SWT.PASSWORD);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(passwordField);

        return area;
    }

    @Override
    protected void okPressed() {
        identifier = identifierField.getText().trim();
        password = passwordField.getText();
        // Empty fields keep the dialog open - a login that cannot work is
        // not a successful OK.
        if (identifier.isEmpty() || password.isEmpty()) {
            return;
        }
        super.okPressed();
    }
}
