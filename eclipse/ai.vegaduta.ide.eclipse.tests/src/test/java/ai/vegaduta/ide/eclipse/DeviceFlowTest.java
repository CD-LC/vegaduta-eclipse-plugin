// The device-flow state machine, tested as pure functions. Covers the three
// things RFC 8628 gets wrong in naive implementations: authorization_pending
// is not a failure, slow_down must widen the interval, and expired/denied must
// stop the loop rather than spin.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.api.ApiException;
import ai.vegaduta.ide.eclipse.auth.DeviceFlow;

class DeviceFlowTest {

    private static final long NOW = 1_700_000_000_000L;

    // --- step 1 --------------------------------------------------------------

    @Test
    @DisplayName("parses a device-authorization response and prefers the complete URI")
    void parseStart() {
        String body = "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\","
                + "\"verification_uri\":\"https://auth.vegaduta.ai/device\","
                + "\"verification_uri_complete\":\"https://auth.vegaduta.ai/device?user_code=ABCD-EFGH\","
                + "\"interval\":7,\"expires_in\":900}";
        DeviceFlow.Start start = DeviceFlow.parseStart(200, body, NOW);
        assertEquals("dc", start.deviceCode());
        assertEquals("ABCD-EFGH", start.userCode());
        assertEquals("https://auth.vegaduta.ai/device?user_code=ABCD-EFGH", start.verificationUri());
        assertEquals(7000, start.intervalMs());
        assertEquals(NOW + 900_000, start.expiresAtMs());
    }

    @Test
    @DisplayName("falls back to verification_uri and to the RFC defaults")
    void parseStartDefaults() {
        DeviceFlow.Start start = DeviceFlow.parseStart(200,
                "{\"device_code\":\"dc\",\"user_code\":\"C\",\"verification_uri\":\"https://a/d\"}", NOW);
        assertEquals("https://a/d", start.verificationUri());
        assertEquals(5000, start.intervalMs());
        assertEquals(NOW + 600_000, start.expiresAtMs());
    }

    @Test
    @DisplayName("a non-2xx or incomplete start surfaces the server's own description")
    void parseStartFailures() {
        ApiException withDescription = assertThrows(ApiException.class, () -> DeviceFlow.parseStart(
                400, "{\"error\":\"unauthorized_client\",\"error_description\":\"Client not allowed\"}", NOW));
        assertEquals("Client not allowed", withDescription.getMessage());
        assertEquals(400, withDescription.status());

        ApiException withError = assertThrows(ApiException.class,
                () -> DeviceFlow.parseStart(400, "{\"error\":\"unauthorized_client\"}", NOW));
        assertEquals("unauthorized_client", withError.getMessage());

        // The fallback names the client that has to exist, because "the device
        // grant is not enabled" is invisible from the raw response.
        ApiException opaque = assertThrows(ApiException.class,
                () -> DeviceFlow.parseStart(404, "<html>not found</html>", NOW));
        assertTrue(opaque.getMessage().contains("agentic-ai-ide"), opaque.getMessage());

        // 200 with a missing device_code is still unusable.
        assertThrows(ApiException.class,
                () -> DeviceFlow.parseStart(200, "{\"user_code\":\"C\"}", NOW));
    }

    @Test
    @DisplayName("a start with no verification URI at all is a typed failure")
    void parseStartNoUri() {
        ApiException error = assertThrows(ApiException.class,
                () -> DeviceFlow.parseStart(200, "{\"device_code\":\"dc\",\"user_code\":\"C\"}", NOW));
        assertTrue(error.getMessage().contains("verification URI"), error.getMessage());
    }

    // --- step 3 --------------------------------------------------------------

    @Test
    @DisplayName("2xx is success")
    void pollSuccess() {
        assertEquals(DeviceFlow.PollKind.SUCCESS, DeviceFlow.classifyPoll(200, "{\"access_token\":\"t\"}").kind());
    }

    @Test
    @DisplayName("authorization_pending keeps polling and is not an error")
    void pollPending() {
        DeviceFlow.Poll poll = DeviceFlow.classifyPoll(400, "{\"error\":\"authorization_pending\"}");
        assertEquals(DeviceFlow.PollKind.PENDING, poll.kind());
        assertNull(poll.message());
    }

    @Test
    @DisplayName("slow_down asks for a wider interval")
    void pollSlowDown() {
        assertEquals(DeviceFlow.PollKind.SLOW_DOWN,
                DeviceFlow.classifyPoll(400, "{\"error\":\"slow_down\"}").kind());
        assertEquals(5000, DeviceFlow.SLOW_DOWN_INCREMENT_MS);
    }

    @Test
    @DisplayName("expired_token and access_denied stop the loop with their own wording")
    void pollTerminalErrors() {
        DeviceFlow.Poll expired = DeviceFlow.classifyPoll(400, "{\"error\":\"expired_token\"}");
        assertEquals(DeviceFlow.PollKind.FAILED, expired.kind());
        assertTrue(expired.message().contains("expired"), expired.message());

        DeviceFlow.Poll denied = DeviceFlow.classifyPoll(400, "{\"error\":\"access_denied\"}");
        assertEquals(DeviceFlow.PollKind.FAILED, denied.kind());
        assertTrue(denied.message().contains("denied"), denied.message());
    }

    @Test
    @DisplayName("an unknown error is a failure carrying the best text available")
    void pollUnknownError() {
        assertEquals("Realm is offline",
                DeviceFlow.classifyPoll(500, "{\"error_description\":\"Realm is offline\"}").message());
        assertEquals("invalid_client",
                DeviceFlow.classifyPoll(401, "{\"error\":\"invalid_client\"}").message());
        assertEquals("Token request failed (503)", DeviceFlow.classifyPoll(503, "<html>").message());
    }

    // --- the token response --------------------------------------------------

    @Test
    @DisplayName("parses tokens and applies the 30s expiry safety margin")
    void parseTokens() {
        String body = "{\"access_token\":\"" + jwt("{\"preferred_username\":\"punee\"}")
                + "\",\"expires_in\":300,\"refresh_token\":\"rt\"}";
        DeviceFlow.Tokens tokens = DeviceFlow.parseTokens(body, NOW);
        assertEquals(NOW + (300 - 30) * 1000, tokens.expiresAtMs());
        assertEquals("punee", tokens.username());
        assertEquals("rt", tokens.refreshToken());
    }

    @Test
    @DisplayName("falls back to the email claim, then to no username at all")
    void usernameFallback() {
        assertEquals("a@b.c", DeviceFlow.parseTokens(
                "{\"access_token\":\"" + jwt("{\"email\":\"a@b.c\"}") + "\"}", NOW).username());
        assertNull(DeviceFlow.parseTokens(
                "{\"access_token\":\"" + jwt("{\"sub\":\"1\"}") + "\"}", NOW).username());
        // A token that is not a JWT at all must not break sign-in.
        assertNull(DeviceFlow.parseTokens("{\"access_token\":\"opaque\"}", NOW).username());
    }

    @Test
    @DisplayName("a very short expiry never produces a negative validity window")
    void shortExpiryClamped() {
        assertEquals(NOW, DeviceFlow.parseTokens(
                "{\"access_token\":\"t\",\"expires_in\":5}", NOW).expiresAtMs());
    }

    @Test
    @DisplayName("an absent refresh_token is reported as null, so the stored one is kept")
    void absentRefreshToken() {
        assertNull(DeviceFlow.parseTokens("{\"access_token\":\"t\"}", NOW).refreshToken());
    }

    @Test
    @DisplayName("a response with no access_token is a typed failure")
    void noAccessToken() {
        assertThrows(ApiException.class, () -> DeviceFlow.parseTokens("{\"refresh_token\":\"r\"}", NOW));
        assertThrows(ApiException.class, () -> DeviceFlow.parseTokens("not json", NOW));
    }

    @Test
    @DisplayName("claim() is best-effort and never throws on a malformed token")
    void claimIsTotal() {
        assertNull(DeviceFlow.claim("only-one-part", "sub"));
        assertNull(DeviceFlow.claim("a.!!!not-base64!!!.c", "sub"));
        assertNull(DeviceFlow.claim("a." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes(StandardCharsets.UTF_8)) + ".c", "sub"));
    }

    // --- form encoding -------------------------------------------------------

    @Test
    @DisplayName("form encoding escapes the values Keycloak actually receives")
    void formEncoding() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        fields.put("client_id", "agentic-ai-ide");
        fields.put("scope", "openid offline_access");
        assertEquals(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
                        + "&client_id=agentic-ai-ide&scope=openid+offline_access",
                DeviceFlow.formEncode(fields));
    }

    /** A JWT with the given payload; header and signature are not inspected. */
    private static String jwt(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    // --- rate limiting (2026-09-18: Cloudflare 429 then IP ban on auth) -------

    @Test
    @DisplayName("a 429 with Cloudflare's HTML body is RATE_LIMITED, not a failed sign-in")
    void http429IsRateLimited() {
        assertEquals(DeviceFlow.PollKind.RATE_LIMITED,
            DeviceFlow.classifyPoll(429, "<html>Error 1015 - You are being rate limited</html>").kind());
    }

    @Test
    @DisplayName("slow_down adds five seconds")
    void slowDownAddsFive() {
        assertEquals(10_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.SLOW_DOWN, 5_000, null));
    }

    @Test
    @DisplayName("a 429 honours a numeric Retry-After, else at least doubles")
    void rateLimitedBackoff() {
        assertEquals(30_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.RATE_LIMITED, 5_000, "30"));
        assertEquals(10_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.RATE_LIMITED, 5_000, null));
        assertEquals(20_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.RATE_LIMITED, 10_000, null));
        assertEquals(10_000, DeviceFlow.nextIntervalMs(
            DeviceFlow.PollKind.RATE_LIMITED, 5_000, "Fri, 18 Sep 2026 05:00:00 GMT"));
    }

    @Test
    @DisplayName("backoff is capped at sixty seconds, and PENDING leaves the interval alone")
    void backoffCapped() {
        assertEquals(60_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.RATE_LIMITED, 5_000, "900"));
        assertEquals(60_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.SLOW_DOWN, 58_000, null));
        assertEquals(5_000, DeviceFlow.nextIntervalMs(DeviceFlow.PollKind.PENDING, 5_000, "30"));
    }
}
