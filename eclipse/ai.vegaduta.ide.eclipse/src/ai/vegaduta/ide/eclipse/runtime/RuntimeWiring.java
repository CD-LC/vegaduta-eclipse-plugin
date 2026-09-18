// How the bundled runtime plugs into the plugin's EXISTING "Local inference
// server" settings (Base URL + Model), and what the chat page is told about it.
//
// Pure Java behind a small settings interface, so the rules are unit-tested
// (RuntimeWiringTest) without an Eclipse preference store.
//
// The rules:
//  - Once llama-server is healthy, its http://127.0.0.1:<port> and the model
//    id (which is also its --alias, so /v1/models lists exactly that name)
//    become the local-server Base URL and Model. The URL is ALSO remembered
//    as "the bundled one", so the plugin can tell its own entry from a URL
//    the person typed.
//  - When the bundled server stops, the local-server settings are cleared
//    ONLY if they still hold the bundled URL. A person who has since typed
//    their own Ollama address keeps it.
//  - hostLocalEngine is true only while the configured local server IS the
//    running bundled server. That is the one server whose CORS behaviour is
//    known (llama-server b11037 reflects the request's Origin by default, so
//    the file:// chat page's fetch is allowed); a user's own Ollama rejects
//    that page's Origin in its default setup, so it stays a status check.

package ai.vegaduta.ide.eclipse.runtime;

import java.util.Map;

import ai.vegaduta.ide.eclipse.json.Json;

public final class RuntimeWiring {

    /** The slice of plugin settings the runtime reads and writes. */
    public interface LocalServerSettings {
        String localServerBaseUrl();

        String localServerModel();

        /** Writes Base URL and Model together (null clears). */
        void setLocalServer(String baseUrl, String model);

        /** The URL the bundled runtime last wrote, or null. */
        String bundledBaseUrl();

        void setBundledBaseUrl(String baseUrl);
    }

    private RuntimeWiring() {
    }

    /** Called once the server answers its health check. */
    public static void onRunning(LocalServerSettings settings, String baseUrl, String modelId) {
        settings.setLocalServer(baseUrl, modelId);
        settings.setBundledBaseUrl(baseUrl);
    }

    /**
     * Called when the bundled server is gone (stopped, crashed, or found dead
     * at startup). Returns true when the local-server settings were cleared.
     */
    public static boolean onStopped(LocalServerSettings settings) {
        String bundled = settings.bundledBaseUrl();
        boolean ours = bundled != null && sameUrl(bundled, settings.localServerBaseUrl());
        if (ours) {
            settings.setLocalServer(null, null);
        }
        if (bundled != null) {
            settings.setBundledBaseUrl(null);
        }
        return ours;
    }

    /** Whether the chat page should run its local-HTTP engine (see header). */
    public static boolean hostLocalEngine(String configuredBaseUrl, RuntimeStatus status) {
        return status != null
                && status.state() == RuntimeStatus.State.RUNNING
                && status.baseUrl() != null
                && sameUrl(status.baseUrl(), configuredBaseUrl);
    }

    /**
     * init.edgeSettings: the local-server address and model as the page's
     * engine reads them (edge/ollamaEngine.ts). Sent on every init so a server
     * started AFTER the page loaded is picked up without a reload. An empty
     * string removes the key (applyEdgeSettings treats null and "" alike).
     */
    public static Map<String, Object> edgeSettings(String baseUrl, String model) {
        Map<String, Object> settings = Json.object();
        settings.put("edge.ollamaBaseUrl", baseUrl == null ? "" : baseUrl);
        settings.put("edge.ollamaModel", model == null ? "" : model);
        return settings;
    }

    static boolean sameUrl(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return trim(a).equalsIgnoreCase(trim(b));
    }

    private static String trim(String url) {
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
