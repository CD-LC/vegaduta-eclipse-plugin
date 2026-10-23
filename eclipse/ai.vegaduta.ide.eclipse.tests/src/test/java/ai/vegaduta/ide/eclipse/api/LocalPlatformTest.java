// Wire-shape coverage for LocalPlatform's /api/auth/local parsing - the
// camelCase SessionResponse (NOT the OIDC snake_case). No network: the test
// calls the package-private parseSession on a decoded map, the way post()
// does after a 200. That package-private access is why this test lives in
// the api package rather than the tests root package.

package ai.vegaduta.ide.eclipse.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class LocalPlatformTest {

    private static final String SESSION_BODY = """
        {
          "accessToken": "acc-token",
          "tokenType": "Bearer",
          "expiresIn": 3600,
          "refreshToken": "ref-token",
          "refreshExpiresIn": 2592000,
          "passwordChangeRequired": false,
          "user": {
            "id": "u1", "email": "me@local", "firstName": "Me",
            "lastName": "", "tenantId": "t", "roles": []
          }
        }
        """;

    private static LocalPlatform.Session session(String body) {
        return LocalPlatform.parseSession(Json.parseObject(body));
    }

    @Test
    void parsesASessionResponse() {
        LocalPlatform.Session s = session(SESSION_BODY);
        assertNotNull(s);
        assertEquals("acc-token", s.accessToken());
        assertEquals("ref-token", s.refreshToken());
        assertEquals(3600, s.expiresIn());
        assertEquals("Me", s.username());
        assertFalse(s.passwordChangeRequired());
    }

    @Test
    void fallsBackToEmailForTheDisplayName() {
        String body = SESSION_BODY.replace("\"firstName\": \"Me\"", "\"firstName\": \" \"");
        assertEquals("me@local", session(body).username());
    }

    @Test
    void toleratesAMissingRefreshToken() {
        String body = SESSION_BODY.replace("\"refreshToken\": \"ref-token\",", "");
        LocalPlatform.Session s = session(body);
        assertNotNull(s);
        assertNull(s.refreshToken());
    }

    @Test
    void rejectsBodyWithNoAccessToken() {
        assertNull(LocalPlatform.parseSession(new LinkedHashMap<>()));
        assertNull(session("{\"error\":\"bad_credentials\",\"message\":\"Wrong.\"}"));
    }

    @Test
    void flagsPasswordChangeRequired() {
        String body = SESSION_BODY.replace("\"passwordChangeRequired\": false", "\"passwordChangeRequired\": true");
        assertEquals(true, session(body).passwordChangeRequired());
    }
}
