// Window > Preferences > VegaDuta.
//
// Hand-built SWT rather than FieldEditorPreferencePage for two reasons: the
// two credential fields do not go to the preference store at all (they go to
// ISecurePreferences via TokenStore), and the custom-environment fields need
// live RULE 0 validation, which a FieldEditor cannot express cleanly.

package ai.vegaduta.ide.eclipse.settings;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.auth.TokenStore;
import ai.vegaduta.ide.eclipse.ui.VegadutaView;

public class VegadutaPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    /** Combo order; index maps to these ids. */
    private static final String[] ENV_IDS = {
        Environments.ENV_PRODUCTION, Environments.ENV_STAGING, Environments.ENV_CUSTOM
    };
    private static final String[] ENV_LABELS = {
        "Production (api.vegaduta.ai)",
        "Staging (api.vegaduta.xyz)",
        "Custom (self-hosted)"
    };

    private Combo environment;
    private Text customApiBase;
    private Text customAuthBase;
    private Text apiKey;
    private Text localServerBaseUrl;
    private Text localServerModel;
    private Text agentBaseUrl;
    private Text agentModel;
    private Spinner agentMaxSteps;
    private Button agentAutoApproveEdits;
    private Button agentAutoApproveCommands;
    private Text agentApiKey;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("Agent chat, workflow runs and on-device inference for Eclipse.");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createEnvironmentGroup(root);
        createLocalInferenceGroup(root);
        createAgentGroup(root);

        load();
        updateCustomEnabled();
        validate();
        return root;
    }

    // --- environment ---------------------------------------------------------

    private void createEnvironmentGroup(Composite parent) {
        Group group = group(parent, "VegaDuta tenant");

        new Label(group, SWT.NONE).setText("Environment:");
        environment = new Combo(group, SWT.READ_ONLY);
        environment.setItems(ENV_LABELS);
        environment.setLayoutData(fill());
        environment.addListener(SWT.Selection, e -> {
            updateCustomEnabled();
            validate();
        });

        new Label(group, SWT.NONE).setText("Custom API base:");
        customApiBase = new Text(group, SWT.BORDER);
        customApiBase.setMessage("https://api.example.com");
        customApiBase.setLayoutData(fill());
        customApiBase.addListener(SWT.Modify, e -> validate());

        new Label(group, SWT.NONE).setText("Custom auth base:");
        customAuthBase = new Text(group, SWT.BORDER);
        customAuthBase.setMessage("https://auth.example.com");
        customAuthBase.setLayoutData(fill());
        customAuthBase.addListener(SWT.Modify, e -> validate());

        new Label(group, SWT.NONE).setText("API key (vmcp_):");
        apiKey = new Text(group, SWT.BORDER | SWT.PASSWORD);
        apiKey.setLayoutData(fill());

        hint(group, "A scoped vmcp_ key is the headless fallback for the interactive sign-in. It"
                + " reaches only the blocking /api/dev/v1 surface, so replies arrive in one piece"
                + " instead of streaming. Stored encrypted in Eclipse's secure storage, never in"
                + " this preference file.");
    }

    // --- local inference -----------------------------------------------------

    private void createLocalInferenceGroup(Composite parent) {
        Group group = group(parent, "Local inference server");

        new Label(group, SWT.NONE).setText("Base URL:");
        localServerBaseUrl = new Text(group, SWT.BORDER);
        localServerBaseUrl.setMessage("http://127.0.0.1:11434");
        localServerBaseUrl.setLayoutData(fill());

        new Label(group, SWT.NONE).setText("Model:");
        localServerModel = new Text(group, SWT.BORDER);
        localServerModel.setMessage("(first model the server lists)");
        localServerModel.setLayoutData(fill());

        hint(group, "Your own OpenAI-compatible server - Ollama, LM Studio, llama.cpp. This is the"
                + " one address in this page that may be a loopback URL: it is your machine, not a"
                + " VegaDuta service. Blank means local inference is off, and nothing is probed."
                + " The plugin reports what it finds at this address in the status line under the"
                + " VegaDuta view.");
    }

    // --- coding agent --------------------------------------------------------

    private void createAgentGroup(Composite parent) {
        Group group = group(parent, "Coding agent");

        new Label(group, SWT.NONE).setText("Endpoint:");
        agentBaseUrl = new Text(group, SWT.BORDER);
        agentBaseUrl.setMessage("(discover a local server)");
        agentBaseUrl.setLayoutData(fill());

        new Label(group, SWT.NONE).setText("Model:");
        agentModel = new Text(group, SWT.BORDER);
        agentModel.setMessage("(first model the endpoint lists)");
        agentModel.setLayoutData(fill());

        new Label(group, SWT.NONE).setText("Max steps:");
        agentMaxSteps = new Spinner(group, SWT.BORDER);
        agentMaxSteps.setMinimum(VegadutaSettings.AGENT_MIN_STEPS);
        agentMaxSteps.setMaximum(VegadutaSettings.AGENT_MAX_STEPS);

        new Label(group, SWT.NONE).setText("Provider API key:");
        agentApiKey = new Text(group, SWT.BORDER | SWT.PASSWORD);
        agentApiKey.setLayoutData(fill());

        new Label(group, SWT.NONE).setText("");
        agentAutoApproveEdits = new Button(group, SWT.CHECK);
        agentAutoApproveEdits.setText("Apply file edits without asking");

        new Label(group, SWT.NONE).setText("");
        agentAutoApproveCommands = new Button(group, SWT.CHECK);
        agentAutoApproveCommands.setText("Run commands without asking");

        hint(group, "These settings are stored for parity with the VS Code and JetBrains plugins."
                + " The coding agent itself is NOT implemented in this Eclipse plugin yet - see"
                + " the README. The provider key is stored in secure storage and is never sent to"
                + " VegaDuta.");
    }

    // --- load / save ---------------------------------------------------------

    private void load() {
        environment.select(indexOfEnvironment(VegadutaSettings.environment()));
        IEclipsePreferences node = VegadutaSettings.node();
        customApiBase.setText(node.get(VegadutaSettings.P_CUSTOM_API_BASE, ""));
        customAuthBase.setText(node.get(VegadutaSettings.P_CUSTOM_AUTH_BASE, ""));
        localServerBaseUrl.setText(node.get(VegadutaSettings.P_LOCAL_SERVER_BASE_URL, ""));
        localServerModel.setText(node.get(VegadutaSettings.P_LOCAL_SERVER_MODEL, ""));
        agentBaseUrl.setText(node.get(VegadutaSettings.P_AGENT_BASE_URL, ""));
        agentModel.setText(node.get(VegadutaSettings.P_AGENT_MODEL, ""));
        agentMaxSteps.setSelection(VegadutaSettings.agentMaxSteps());
        agentAutoApproveEdits.setSelection(VegadutaSettings.agentAutoApproveEdits());
        agentAutoApproveCommands.setSelection(VegadutaSettings.agentAutoApproveCommands());
        // Secrets are NOT read back into the fields. A blank field means "leave
        // the stored value alone"; the placeholder says whether one exists.
        apiKey.setMessage(TokenStore.get().apiKey() != null ? "(stored - type to replace)" : "");
        agentApiKey.setMessage(TokenStore.get().agentApiKey() != null ? "(stored - type to replace)" : "");
    }

    private static int indexOfEnvironment(String id) {
        for (int i = 0; i < ENV_IDS.length; i++) {
            if (ENV_IDS[i].equals(id)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public boolean performOk() {
        if (!validate()) {
            return false;
        }
        IEclipsePreferences node = VegadutaSettings.node();
        node.put(VegadutaSettings.P_ENVIRONMENT, ENV_IDS[Math.max(0, environment.getSelectionIndex())]);
        node.put(VegadutaSettings.P_CUSTOM_API_BASE, customApiBase.getText().trim());
        node.put(VegadutaSettings.P_CUSTOM_AUTH_BASE, customAuthBase.getText().trim());
        node.put(VegadutaSettings.P_LOCAL_SERVER_BASE_URL, localServerBaseUrl.getText().trim());
        node.put(VegadutaSettings.P_LOCAL_SERVER_MODEL, localServerModel.getText().trim());
        node.put(VegadutaSettings.P_AGENT_BASE_URL, agentBaseUrl.getText().trim());
        node.put(VegadutaSettings.P_AGENT_MODEL, agentModel.getText().trim());
        node.putInt(VegadutaSettings.P_AGENT_MAX_STEPS, agentMaxSteps.getSelection());
        node.putBoolean(VegadutaSettings.P_AGENT_AUTO_APPROVE_EDITS, agentAutoApproveEdits.getSelection());
        node.putBoolean(VegadutaSettings.P_AGENT_AUTO_APPROVE_COMMANDS,
                agentAutoApproveCommands.getSelection());
        try {
            node.flush();
        } catch (BackingStoreException e) {
            VegadutaPlugin.logError("Could not save VegaDuta preferences", e);
            setErrorMessage("Could not save preferences - see the Error Log.");
            return false;
        }
        // Only write a credential the user actually typed. A blank field means
        // "keep what is stored" - see load().
        if (!apiKey.getText().isBlank()) {
            TokenStore.get().setApiKey(apiKey.getText());
            apiKey.setText("");
        }
        if (!agentApiKey.getText().isBlank()) {
            TokenStore.get().setAgentApiKey(agentApiKey.getText());
            agentApiKey.setText("");
        }
        // apiBase and the edge seed are baked into the chat document when it
        // loads, so a settings change only takes effect on a reload.
        VegadutaView.reloadAll();
        return true;
    }

    @Override
    protected void performDefaults() {
        environment.select(0);
        customApiBase.setText("");
        customAuthBase.setText("");
        localServerBaseUrl.setText("");
        localServerModel.setText("");
        agentBaseUrl.setText("");
        agentModel.setText("");
        agentMaxSteps.setSelection(VegadutaSettings.AGENT_DEFAULT_MAX_STEPS);
        agentAutoApproveEdits.setSelection(false);
        agentAutoApproveCommands.setSelection(false);
        updateCustomEnabled();
        validate();
        super.performDefaults();
    }

    // --- validation ----------------------------------------------------------

    private void updateCustomEnabled() {
        boolean custom = Environments.ENV_CUSTOM.equals(ENV_IDS[Math.max(0, environment.getSelectionIndex())]);
        customApiBase.setEnabled(custom);
        customAuthBase.setEnabled(custom);
    }

    /**
     * RULE 0, enforced where the user can see it: a platform API base or token
     * issuer may never be a loopback address. The local inference server, on
     * purpose, is NOT checked - that one IS the user's own machine.
     */
    private boolean validate() {
        setErrorMessage(null);
        if (!Environments.ENV_CUSTOM.equals(ENV_IDS[Math.max(0, environment.getSelectionIndex())])) {
            setValid(true);
            return true;
        }
        if (Environments.isLoopback(customApiBase.getText())
                || Environments.isLoopback(customAuthBase.getText())) {
            setErrorMessage("A VegaDuta API or auth base cannot point at localhost - use the public"
                    + " hostname of your deployment.");
            setValid(false);
            return false;
        }
        setValid(true);
        return true;
    }

    // --- small SWT helpers ---------------------------------------------------

    private static Group group(Composite parent, String text) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(text);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return group;
    }

    private static GridData fill() {
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 320;
        return data;
    }

    private static void hint(Composite parent, String text) {
        Label label = new Label(parent, SWT.WRAP);
        GridData data = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        data.widthHint = 420;
        label.setLayoutData(data);
        label.setText(text);
    }
}
