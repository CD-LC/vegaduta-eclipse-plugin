// The pure half of the OAuth 2.0 Device Authorization Grant (RFC 8628)
// against Keycloak: parsing the device-authorization response, deciding what a
// token-endpoint poll response means, and parsing a token response. No HTTP,
// no Eclipse API, no clock - every decision is a function of its arguments, so
// the whole state machine is unit-tested (DeviceFlowTest).
//
// The transport half is DeviceFlowLogin; the storage half is TokenStore.
//
// Twin of clients/shared/src/auth/deviceFlow.ts and
// clients/jetbrains/.../auth/DeviceFlowLoginService.kt:
//   1. POST /realms/agentic-ai/protocol/openid-connect/auth/device
//      with client_id=agentic-ai-ide & scope="openid offline_access"
//      (offline_access because realm SSO idle is 4h while offline tokens idle
//      out at 30d - an IDE that is closed overnight must still be signed in);
//   2. open verification_uri_complete in the user's browser;
//   3. poll the token endpoint honouring authorization_pending and slow_down.
//
// The client id and realm are NOT invented here - they are read out of
// deploy/keycloak/agentic-ai-realm.json, where "agentic-ai-ide" is a public
// client with oauth2.device.authorization.grant.enabled = "true".

package ai.vegaduta.ide.eclipse.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import ai.vegaduta.ide.eclipse.api.ApiException;
import ai.vegaduta.ide.eclipse.json.Json;

public final class DeviceFlow {

    /** RFC 8628 default poll interval when the server does not send one. */
    public static final long DEFAULT_INTERVAL_SECONDS = 5;

    /** RFC 8628 3.5: on slow_down the client adds 5 seconds to its interval. */
    public static final long SLOW_DOWN_INCREMENT_MS = 5000;

    /** RFC 8628 default lifetime when the server does not send expires_in. */
    public static final long DEFAULT_EXPIRES_IN_SECONDS = 600;

    /** Access tokens are treated as expired this long BEFORE they really are,
     * so a request cannot be sent with a token that dies in flight. */
    public static final long EXPIRY_SAFETY_MARGIN_SECONDS = 30;

    private DeviceFlow() {
    }

    // --- step 1: the device-authorization response ---------------------------

    public record Start(String deviceCode, String userCode, String verificationUri,
                        long intervalMs, long expiresAtMs) {
    }

    /**
     * Parses the device-authorization response.
     *
     * @throws ApiException with the server's own error_description when the
     *         response is not usable. The fallback message names the client
     *         that has to exist, because "device flow is not enabled on this
     *         Keycloak" is by far the most common cause and is invisible from
     *         the raw error.
     */
    public static Start parseStart(int status, String body, long nowMs) {
        Map<String, Object> o = Json.parseObjectOrEmpty(body);
        String deviceCode = Json.str(o, "device_code");
        String userCode = Json.str(o, "user_code");
        if (status < 200 || status > 299 || deviceCode == null || userCode == null) {
            String detail = Json.str(o, "error_description");
            if (detail == null) {
                detail = Json.str(o, "error");
            }
            throw new ApiException(status, detail != null ? detail
                    : "Could not start device sign-in. Check that the agentic-ai-ide client exists on this"
                            + " Keycloak with the device grant enabled.");
        }
        String verificationUri = Json.str(o, "verification_uri_complete");
        if (verificationUri == null) {
            verificationUri = Json.str(o, "verification_uri");
        }
        if (verificationUri == null) {
            throw new ApiException(0, "The device response carried no verification URI.");
        }
        long intervalMs = Json.num(o, "interval", DEFAULT_INTERVAL_SECONDS) * 1000;
        long expiresAtMs = nowMs + Json.num(o, "expires_in", DEFAULT_EXPIRES_IN_SECONDS) * 1000;
        return new Start(deviceCode, userCode, verificationUri, intervalMs, expiresAtMs);
    }

    // --- step 3: what one poll response means --------------------------------

    public enum PollKind {
        /** Tokens issued - `body` is the token response. */
        SUCCESS,
        /** The user has not finished in the browser yet; poll again unchanged. */
        PENDING,
        /** Polling too fast; poll again after adding SLOW_DOWN_INCREMENT_MS. */
        SLOW_DOWN,
        /** HTTP 429 from the rate-limiting edge (Cloudflare, HTML body - no
         * OAuth error). Treated like SLOW_DOWN but harder: see nextIntervalMs. */
        RATE_LIMITED,
        /** Give up and show `message`. */
        FAILED
    }

    public record Poll(PollKind kind, String message) {
    }

    /** Longest wait between polls once the server wants us slower. */
    public static final long MAX_INTERVAL_MS = 60_000;

    /**
     * The next poll interval after a SLOW_DOWN or RATE_LIMITED poll. Mirrors
     * clients/shared/src/auth/deviceFlow.ts - keep them in step.
     * SLOW_DOWN: +5s (RFC 8628 3.5). RATE_LIMITED: a numeric Retry-After
     * (seconds) when present, else the larger of +5s and doubling. Capped at
     * MAX_INTERVAL_MS. Any other kind leaves the interval unchanged.
     */
    public static long nextIntervalMs(PollKind kind, long currentMs, String retryAfterHeader) {
        long next;
        if (kind == PollKind.SLOW_DOWN) {
            next = currentMs + SLOW_DOWN_INCREMENT_MS;
        } else if (kind == PollKind.RATE_LIMITED) {
            long retryAfterSec = -1;
            if (retryAfterHeader != null) {
                try {
                    retryAfterSec = Long.parseLong(retryAfterHeader.trim());
                } catch (NumberFormatException ignored) {
                    retryAfterSec = -1; // an HTTP-date Retry-After: fall back
                }
            }
            next = retryAfterSec > 0
                ? retryAfterSec * 1000
                : Math.max(currentMs + SLOW_DOWN_INCREMENT_MS, currentMs * 2);
        } else {
            return currentMs;
        }
        return Math.min(MAX_INTERVAL_MS, next);
    }

    /**
     * Classifies one token-endpoint poll response. Never throws - the caller
     * drives a loop and needs a value, not an exception, for the two outcomes
     * (PENDING, SLOW_DOWN) that are not failures at all.
     */
    public static Poll classifyPoll(int status, String body) {
        if (status >= 200 && status <= 299) {
            return new Poll(PollKind.SUCCESS, null);
        }
        // Before parsing: a 429 body is Cloudflare's HTML, not OAuth JSON.
        // Found 2026-09-18 - auth.vegaduta.ai 429s repeated polls and then
        // BANS the IP (error 1015), browser included. This plugin used to
        // report the 429 as a failed sign-in.
        if (status == 429) {
            return new Poll(PollKind.RATE_LIMITED, null);
        }
        Map<String, Object> o = Json.parseObjectOrEmpty(body);
        String error = Json.str(o, "error");
        if ("authorization_pending".equals(error)) {
            return new Poll(PollKind.PENDING, null);
        }
        if ("slow_down".equals(error)) {
            return new Poll(PollKind.SLOW_DOWN, null);
        }
        if ("expired_token".equals(error)) {
            return new Poll(PollKind.FAILED, "The sign-in code expired. Please start again.");
        }
        if ("access_denied".equals(error)) {
            return new Poll(PollKind.FAILED, "Sign-in was denied in the browser.");
        }
        String detail = Json.str(o, "error_description");
        if (detail == null) {
            detail = error;
        }
        return new Poll(PollKind.FAILED, detail != null ? detail : "Token request failed (" + status + ")");
    }

    // --- the token response --------------------------------------------------

    /** What a Keycloak token response yields. refreshToken is null when the
     * response carried none (in which case the stored one must be KEPT, not
     * cleared - a non-rotating realm returns no new refresh token). */
    public record Tokens(String accessToken, long expiresAtMs, String username, String refreshToken) {
    }

    /**
     * Parses a Keycloak token-endpoint response.
     *
     * @throws ApiException when there is no access_token - the one case where
     *         a "successful" HTTP response is unusable.
     */
    public static Tokens parseTokens(String body, long nowMs) {
        Map<String, Object> o = Json.parseObjectOrEmpty(body);
        String access = Json.str(o, "access_token");
        if (access == null) {
            throw new ApiException(0, "The token response carried no access_token.");
        }
        long expiresIn = Json.num(o, "expires_in", 60);
        long usableFor = Math.max(0, expiresIn - EXPIRY_SAFETY_MARGIN_SECONDS);
        String username = claim(access, "preferred_username");
        if (username == null) {
            username = claim(access, "email");
        }
        return new Tokens(access, nowMs + usableFor * 1000, username, Json.str(o, "refresh_token"));
    }

    /**
     * Best-effort read of a JWT payload claim, for DISPLAY ONLY - the
     * signature is not checked here and this value must never drive an
     * authorization decision. Returns null on anything malformed.
     */
    public static String claim(String jwt, String name) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return Json.str(Json.parseObjectOrEmpty(new String(decoded, StandardCharsets.UTF_8)), name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** application/x-www-form-urlencoded body from ordered fields. */
    public static String formEncode(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            out.append('=');
            out.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}
