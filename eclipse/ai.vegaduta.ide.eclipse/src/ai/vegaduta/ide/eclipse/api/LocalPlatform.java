// Host-side probe for a VegaDuta platform the user is running on their own
// machine (compose lite, or a dev build) - the Java twin of
// clients/shared/src/api/localPlatform.ts, same fingerprint and same
// never-throws contract as LocalServerProbe.
//
// RULE 0 note: this is the one case where a loopback platform URL is
// legitimate - the user's own install, explicitly opted into (the "local"
// environment, or the chat view's Connect button). Nothing here ever becomes
// a default apiBase; it only confirms a server is there so the UI can offer
// to connect.
//
// Fingerprint, not a guess: 200 + {"status":"UP"} on /api/system/status/live
// (core's permitAll liveness probe, SystemStatusController.live()) AND
// /api/agents answering the way a VegaDuta core answers an unauthenticated
// caller - 401/403 (secured) or 200 (a dev profile without the security
// filter). A stray app that happens to return the liveness shape but 404s
// /api/agents is not reported.

package ai.vegaduta.ide.eclipse.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import ai.vegaduta.ide.eclipse.json.Json;

public final class LocalPlatform {

    /** How a locally running install authenticates:
     *  KEYCLOAK - the agentic-ai realm answers on authBase (lite/full local
     *    install): interactive sign-in is the device flow.
     *  LOCAL    - /api/auth/local/config answered mode:"local" (a personal
     *    install, app.auth.mode=local - accounts live in core, no Keycloak
     *    exists by design): sign-in is identifier + password on
     *    /api/auth/local/login.
     *  null     - neither answered (still booting, or an unusual install):
     *    the vmcp_ API-key path is the honest fallback. */
    public enum AuthMode { KEYCLOAK, LOCAL }

    /** What a probe established about a locally running platform. */
    public record Info(String apiBase, String authBase, AuthMode authMode) {
        /** authBase is null when the agentic-ai realm did not answer - the
         * device flow cannot start then. authMode is null when no sign-in at
         * all answered, and only the vmcp_ API-key path is guaranteed. */
    }

    /** Same budget as shared's PROBE_TIMEOUT_MS: loopback refuses instantly,
     * so this only bounds something that accepts the socket and stalls. */
    private static final long PROBE_TIMEOUT_MS = 1200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .build();

    private LocalPlatform() {
    }

    /** Probes one candidate pair. Returns null when nothing VegaDuta-shaped
     * answers at {@code apiBase}; never throws. Call off the UI thread. */
    public static Info probe(String apiBase, String authBase) {
        String api = apiBase == null ? "" : apiBase.trim();
        while (api.endsWith("/")) {
            api = api.substring(0, api.length() - 1);
        }
        if (api.isBlank() || !liveUp(api) || !agentsEndpointExists(api)) {
            return null;
        }
        String auth = authBase == null ? "" : authBase.trim();
        while (auth.endsWith("/")) {
            auth = auth.substring(0, auth.length() - 1);
        }
        if (!auth.isBlank() && realmAnswers(auth)) {
            return new Info(api, auth, AuthMode.KEYCLOAK);
        }
        // No Keycloak: a personal install (accounts in core) or nothing to
        // sign in with at all - the probe's authMode says which.
        return new Info(api, null, localAuthAnswers(api) ? AuthMode.LOCAL : null);
    }

    private static HttpResponse<String> get(String url) {
        try {
            return HTTP.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean liveUp(String apiBase) {
        HttpResponse<String> response = get(apiBase + "/api/system/status/live");
        if (response == null || response.statusCode() < 200 || response.statusCode() > 299) {
            return false;
        }
        try {
            Object parsed = Json.parse(response.body());
            return parsed instanceof Map<?, ?> map && "UP".equals(map.get("status"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean agentsEndpointExists(String apiBase) {
        HttpResponse<String> response = get(apiBase + "/api/agents");
        if (response == null) {
            return false;
        }
        int status = response.statusCode();
        return status == 200 || status == 401 || status == 403;
    }

    private static boolean realmAnswers(String authBase) {
        HttpResponse<String> response = get(authBase + "/realms/" + Models.KEYCLOAK_REALM);
        if (response == null || response.statusCode() < 200 || response.statusCode() > 299) {
            return false;
        }
        try {
            Object parsed = Json.parse(response.body());
            return parsed instanceof Map<?, ?> map && Models.KEYCLOAK_REALM.equals(map.get("realm"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** GET /api/auth/local/config is permitAll and reports {mode:"local"} on
     * a personal install (app.auth.mode=local): accounts and sessions are
     * held by core itself, so identifier + password sign-in on
     * /api/auth/local/login is the path - and there IS no Keycloak to wait
     * for. A keycloak-mode install answers anything else. */
    private static boolean localAuthAnswers(String apiBase) {
        HttpResponse<String> response = get(apiBase + "/api/auth/local/config");
        if (response == null || response.statusCode() < 200 || response.statusCode() > 299) {
            return false;
        }
        try {
            Object parsed = Json.parse(response.body());
            return parsed instanceof Map<?, ?> map && "local".equals(map.get("mode"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // --- local-accounts sessions (personal installs) --------------------------

    /** A login or refresh is a real request the user is waiting on, not a
     * background probe - it gets a real timeout, not the probe budget. */
    private static final long AUTH_TIMEOUT_MS = 10_000;

    /** One /api/auth/local session, as LocalAuthController.SessionResponse
     * returns it - camel-case fields, NOT the OIDC snake_case. */
    public record Session(
            String accessToken,
            /** null when the response carried none (a refresh keeps the old one). */
            String refreshToken,
            /** Server-reported access-token lifetime, seconds. */
            long expiresIn,
            /** firstName when set, else the account's email - display only. */
            String username,
            boolean passwordChangeRequired) {
    }

    /** Typed outcome, never a throw: the refusals are LocalAuthException's
     * {error, message} shape and anything else is a transport/parse problem.
     * status is the HTTP status when one arrived - a refused refresh means
     * the session is gone (drop it); an unreachable server is transient. */
    public sealed interface AuthResult {
        record Ok(Session session) implements AuthResult {
        }
        record Failed(String message, Integer status) implements AuthResult {
        }
    }

    /** POST /api/auth/local/login - identifier + password on a personal
     * install. Same login the console's own sign-in page posts. */
    public static AuthResult login(String apiBase, String identifier, String password) {
        Map<String, Object> body = Json.object();
        body.put("identifier", identifier);
        body.put("password", password);
        return post(apiBase, "/login", Json.write(body));
    }

    /** POST /api/auth/local/refresh - mint a new access token from the stored
     * refresh token. A refused refresh means the session is gone, exactly
     * like a revoked OIDC refresh token. */
    public static AuthResult refresh(String apiBase, String refreshToken) {
        Map<String, Object> body = Json.object();
        body.put("refreshToken", refreshToken);
        return post(apiBase, "/refresh", Json.write(body));
    }

    private static AuthResult post(String apiBase, String path, String jsonBody) {
        HttpResponse<String> response;
        try {
            response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(apiBase + "/api/auth/local" + path))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofMillis(AUTH_TIMEOUT_MS))
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AuthResult.Failed("Interrupted.", null);
        } catch (Exception e) {
            return new AuthResult.Failed("Could not reach " + apiBase + ".", null);
        }
        // Empty map on an unparseable or non-object body - a refusal with a
        // garbage body must not become a second failure on top of the first.
        Map<String, Object> parsed = Json.parseObjectOrEmpty(response.body());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            Object message = parsed.get("message");
            return new AuthResult.Failed(
                    message instanceof String m && !m.isBlank()
                            ? m
                            : "Sign-in refused (" + response.statusCode() + ").",
                    response.statusCode());
        }
        Session session = parseSession(parsed);
        return session != null
                ? new AuthResult.Ok(session)
                : new AuthResult.Failed("The server answered, but not with a session.", response.statusCode());
    }

    /** Parses a SessionResponse map. Package-private (not private) so unit
     * tests cover the wire shape without a live install. */
    static Session parseSession(Map<String, Object> obj) {
        Object access = obj.get("accessToken");
        if (!(access instanceof String token) || token.isBlank()) {
            return null;
        }
        Object user = obj.get("user");
        String username = null;
        if (user instanceof Map<?, ?> u) {
            username = nonBlank(u.get("firstName"));
            if (username == null) {
                username = nonBlank(u.get("email"));
            }
        }
        return new Session(
                token,
                nonBlank(obj.get("refreshToken")),
                numberOr(obj.get("expiresIn"), 60),
                username,
                Boolean.TRUE.equals(obj.get("passwordChangeRequired")));
    }

    private static String nonBlank(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static long numberOr(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }
}
