// Token storage. Mirrors clients/jetbrains/.../auth/TokenStore.kt and
// clients/shared/src/auth/oidc.ts:
//   - the ACCESS token lives in memory only, with a 30s expiry safety margin;
//   - the REFRESH token, the pasted vmcp_ API key and the coding agent's BYOK
//     provider key live in Eclipse's ISecurePreferences, NEVER in the ordinary
//     preference store (which is a plain file that syncs between machines);
//   - refresh is grant_type=refresh_token against the environment's Keycloak
//     (client agentic-ai-ide, realm agentic-ai).
//
// Token rule, the same one VS Code and JetBrains enforce: no token in this
// class ever reaches the webview. Every authenticated call runs host-side.

package ai.vegaduta.ide.eclipse.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.api.Models;
import ai.vegaduta.ide.eclipse.json.Json;
import ai.vegaduta.ide.eclipse.settings.Environments;
import ai.vegaduta.ide.eclipse.settings.VegadutaSettings;

public final class TokenStore {

    /** The ISecurePreferences node everything below lives under. */
    private static final String SECURE_NODE = "ai.vegaduta.ide.eclipse";
    private static final String KEY_REFRESH_TOKEN = "refresh-token";
    private static final String KEY_API_KEY = "api-key";

    /** The coding agent's BYOK key for a third-party OpenAI-compatible
     * provider. A separate slot on purpose: it is NOT a VegaDuta credential and
     * must never reach bearerToken(), which would send the user's own
     * provider key to the platform. */
    private static final String KEY_AGENT_API_KEY = "agent-api-key";

    private static final TokenStore INSTANCE = new TokenStore();

    public static TokenStore get() {
        return INSTANCE;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final CopyOnWriteArrayList<Runnable> authListeners = new CopyOnWriteArrayList<>();

    private volatile String accessToken;
    private volatile long expiresAtMs;
    private volatile String username;

    private TokenStore() {
    }

    // --- listeners -----------------------------------------------------------

    /** Fired when the signed-in state flips (sign-in completed / signed out /
     * API key set or cleared). Listener failures are swallowed: one broken
     * view must not stop the others from updating. */
    public void addAuthListener(Runnable listener) {
        authListeners.add(listener);
    }

    public void removeAuthListener(Runnable listener) {
        authListeners.remove(listener);
    }

    private void fireAuthChanged() {
        for (Runnable listener : authListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                VegadutaPlugin.logInfo("auth listener failed: " + e.getMessage());
            }
        }
    }

    // --- state ---------------------------------------------------------------

    public boolean isSignedIn() {
        return accessToken != null || refreshToken() != null;
    }

    public String username() {
        return username;
    }

    /** "jwt" when interactively signed in, "apiKey" when only a pasted key
     * exists, null when neither. Matches AuthState.mode in the shared
     * protocol. */
    public String authMode() {
        if (isSignedIn()) {
            return "jwt";
        }
        if (apiKey() != null) {
            return "apiKey";
        }
        return null;
    }

    /** Bearer for API calls: a fresh access token, else a refresh, else the
     * vmcp_ key. Null when the user is not authenticated at all. */
    public String bearerToken() {
        String token = accessToken;
        if (token != null && System.currentTimeMillis() < expiresAtMs) {
            return token;
        }
        String refreshed = refreshBlocking();
        if (refreshed != null) {
            return refreshed;
        }
        return apiKey();
    }

    /**
     * Blocking refresh-token exchange; returns the new access token or null.
     * Synchronized so parallel 401 handlers do not burn a rotating refresh
     * token by racing each other through it.
     */
    public synchronized String refreshBlocking() {
        String token = accessToken;
        if (token != null && System.currentTimeMillis() < expiresAtMs) {
            return token; // another thread refreshed while we waited on the lock
        }
        String refresh = refreshToken();
        if (refresh == null) {
            return null;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", Models.IDE_CLIENT_ID);
        form.put("refresh_token", refresh);

        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(URI.create(tokenEndpoint()))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(DeviceFlow.formEncode(form)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Network failure: keep the refresh token. The user is probably
            // offline, not signed out.
            VegadutaPlugin.logInfo("token refresh failed (network): " + e.getMessage());
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            String error = Json.str(Json.parseObjectOrEmpty(response.body()), "error");
            // invalid_grant = the refresh token is expired or revoked. Drop it
            // so the UI shows signed-out instead of retrying forever.
            if ("invalid_grant".equals(error)) {
                signOut();
            }
            VegadutaPlugin.logInfo("token refresh rejected: " + error);
            return null;
        }
        try {
            return storeTokenResponse(response.body());
        } catch (RuntimeException e) {
            VegadutaPlugin.logInfo("token response unusable: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses a Keycloak token-endpoint response and stores its contents.
     * Returns the access token. Used by both the device flow and refresh.
     */
    public String storeTokenResponse(String body) {
        DeviceFlow.Tokens tokens = DeviceFlow.parseTokens(body, System.currentTimeMillis());
        boolean wasSignedIn = isSignedIn();
        accessToken = tokens.accessToken();
        expiresAtMs = tokens.expiresAtMs();
        username = tokens.username();
        // Absent refresh_token means "keep the one you have" - a realm with
        // refresh-token rotation off returns none on a refresh, and clearing it
        // would sign the user out on their next restart.
        if (tokens.refreshToken() != null) {
            writeSecret(KEY_REFRESH_TOKEN, tokens.refreshToken());
        }
        if (!wasSignedIn) {
            fireAuthChanged();
        }
        return tokens.accessToken();
    }

    public void signOut() {
        boolean wasSignedIn = isSignedIn();
        accessToken = null;
        expiresAtMs = 0;
        username = null;
        writeSecret(KEY_REFRESH_TOKEN, null);
        if (wasSignedIn) {
            fireAuthChanged();
        }
    }

    // --- the vmcp_ API-key fallback -----------------------------------------

    /** The pasted scoped API key, or null. Authenticates only the blocking
     * /api/dev/v1 surface - see ApiClient.surfacePath(). */
    public String apiKey() {
        return readSecret(KEY_API_KEY);
    }

    public void setApiKey(String key) {
        writeSecret(KEY_API_KEY, key);
        fireAuthChanged();
    }

    /** The coding agent's provider key, or null. Read only by the agent's model
     * adapter; no VegaDuta call ever sees it. No auth listener fires - this key
     * has nothing to do with being signed in to VegaDuta. */
    public String agentApiKey() {
        return readSecret(KEY_AGENT_API_KEY);
    }

    public void setAgentApiKey(String key) {
        writeSecret(KEY_AGENT_API_KEY, key);
    }

    // --- ISecurePreferences --------------------------------------------------

    private String refreshToken() {
        return readSecret(KEY_REFRESH_TOKEN);
    }

    private static ISecurePreferences secureNode() {
        ISecurePreferences root = SecurePreferencesFactory.getDefault();
        return root == null ? null : root.node(SECURE_NODE);
    }

    /**
     * Reads a secret, or null. Never throws: a locked or unreadable secure
     * store must degrade to "not signed in", not break the view. The one
     * observable effect is a log line.
     */
    private String readSecret(String key) {
        ISecurePreferences node = secureNode();
        if (node == null) {
            return null;
        }
        try {
            String value = node.get(key, null);
            return (value == null || value.isBlank()) ? null : value;
        } catch (StorageException e) {
            VegadutaPlugin.logInfo("secure storage unreadable (" + key + "): " + e.getMessage());
            return null;
        }
    }

    /** Writes or (on a null/blank value) removes a secret. Never throws. */
    private void writeSecret(String key, String value) {
        ISecurePreferences node = secureNode();
        if (node == null) {
            VegadutaPlugin.logInfo("secure storage unavailable - " + key + " not stored");
            return;
        }
        try {
            String trimmed = (value == null) ? null : value.trim();
            if (trimmed == null || trimmed.isBlank()) {
                node.remove(key);
            } else {
                // true = encrypt. The whole reason this class exists.
                node.put(key, trimmed, true);
            }
            node.flush();
        } catch (Exception e) {
            VegadutaPlugin.logInfo("secure storage not writable (" + key + "): " + e.getMessage());
        }
    }

    private static String tokenEndpoint() {
        return Environments.openidBase(VegadutaSettings.authBase(), Models.KEYCLOAK_REALM) + "/token";
    }
}
