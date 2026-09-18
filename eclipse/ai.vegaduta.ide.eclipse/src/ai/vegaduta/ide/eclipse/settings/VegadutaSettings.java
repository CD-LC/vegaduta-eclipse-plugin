// Persisted plugin settings, in Eclipse's instance-scope preference store.
// Values only - no secret is ever stored here. The refresh token, the vmcp_
// API key and the coding agent's provider key live in ISecurePreferences
// (see auth/TokenStore), because a preference store is a plain file that
// syncs between machines and shows up in screen shares.
//
// URL resolution (and RULE 0) lives in Environments, which has no Eclipse
// dependency and is unit-tested on its own.

package ai.vegaduta.ide.eclipse.settings;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

public final class VegadutaSettings {

    public static final String NODE = "ai.vegaduta.ide.eclipse";

    public static final String P_ENVIRONMENT = "environment";
    public static final String P_CUSTOM_API_BASE = "customApiBase";
    public static final String P_CUSTOM_AUTH_BASE = "customAuthBase";
    public static final String P_LOCAL_SERVER_BASE_URL = "localServerBaseUrl";
    public static final String P_LOCAL_SERVER_MODEL = "localServerModel";
    public static final String P_AGENT_BASE_URL = "agentBaseUrl";
    public static final String P_AGENT_MODEL = "agentModel";
    public static final String P_AGENT_MAX_STEPS = "agentMaxSteps";
    public static final String P_AGENT_AUTO_APPROVE_EDITS = "agentAutoApproveEdits";
    public static final String P_AGENT_AUTO_APPROVE_COMMANDS = "agentAutoApproveCommands";
    /** Private Mode (privacy/PrivacyGate). Persisted so it survives a restart:
     * someone who turned it on must not find it silently off tomorrow. */
    public static final String P_PRIVATE_MODE = "privateMode";

    /** vegaduta.agent.maxSteps' declared bounds in clients/vscode/package.json,
     * mirrored by the JetBrains plugin. Re-applied on every read rather than
     * trusted from disk: a preference file is editable by hand, and this is the
     * bound on a loop that can edit files and run commands. */
    public static final int AGENT_MIN_STEPS = 1;
    public static final int AGENT_MAX_STEPS = 300;
    public static final int AGENT_DEFAULT_MAX_STEPS = 24;

    private VegadutaSettings() {
    }

    public static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    private static String get(String key, String fallback) {
        return node().get(key, fallback);
    }

    public static String environment() {
        return get(P_ENVIRONMENT, Environments.ENV_PRODUCTION);
    }

    public static String apiBase() {
        return Environments.apiBase(environment(), get(P_CUSTOM_API_BASE, ""));
    }

    public static String authBase() {
        return Environments.authBase(environment(), get(P_CUSTOM_AUTH_BASE, ""));
    }

    /**
     * The user's own OpenAI-compatible inference server, or null when unset.
     *
     * RULE 0 does NOT cover this one (see Environments' header): it is the
     * user's own process on their own machine, and the shared edge layer's
     * ollamaEngine.ts documents the same carve-out. There is deliberately no
     * default - null means local inference is simply off, and nothing is
     * probed until the user types a URL.
     */
    public static String localServerBaseUrl() {
        String value = Environments.trimTrailingSlashes(get(P_LOCAL_SERVER_BASE_URL, ""));
        return value.isBlank() ? null : value;
    }

    /** The model id to ask the local server for, or null to take the first one
     * it lists (ollamaEngine.ts's pickModel() rule). */
    public static String localServerModel() {
        String value = get(P_LOCAL_SERVER_MODEL, "").trim();
        return value.isBlank() ? null : value;
    }

    /** The coding agent's endpoint, or null to let discovery pick one. Same
     * carve-out as localServerBaseUrl: the user's own server, or a provider
     * origin they typed. */
    public static String agentBaseUrl() {
        String value = Environments.trimTrailingSlashes(get(P_AGENT_BASE_URL, ""));
        return value.isBlank() ? null : value;
    }

    public static String agentModel() {
        String value = get(P_AGENT_MODEL, "").trim();
        return value.isBlank() ? null : value;
    }

    public static int agentMaxSteps() {
        int stored = node().getInt(P_AGENT_MAX_STEPS, AGENT_DEFAULT_MAX_STEPS);
        return Math.max(AGENT_MIN_STEPS, Math.min(AGENT_MAX_STEPS, stored));
    }

    public static boolean agentAutoApproveEdits() {
        return node().getBoolean(P_AGENT_AUTO_APPROVE_EDITS, false);
    }

    public static boolean agentAutoApproveCommands() {
        return node().getBoolean(P_AGENT_AUTO_APPROVE_COMMANDS, false);
    }

    public static boolean privateMode() {
        return node().getBoolean(P_PRIVATE_MODE, false);
    }

    /** Stores Private Mode and flushes, so a crash right after the toggle
     * does not lose it. A failed flush is logged; the in-memory value (and so
     * the enforcement) still holds for this session. */
    public static void setPrivateMode(boolean on) {
        IEclipsePreferences node = node();
        node.putBoolean(P_PRIVATE_MODE, on);
        try {
            node.flush();
        } catch (org.osgi.service.prefs.BackingStoreException e) {
            ai.vegaduta.ide.eclipse.VegadutaPlugin.logWarning("could not save the Private Mode preference", e);
        }
    }
}
