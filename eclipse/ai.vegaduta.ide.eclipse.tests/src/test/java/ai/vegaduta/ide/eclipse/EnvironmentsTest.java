// RULE 0 in executable form: vegaduta.ai is production, vegaduta.xyz is
// staging, and no platform URL this plugin resolves may ever be a loopback
// address. If a change makes one of these tests fail, the change is wrong -
// read CLAUDE.md's RULE 0 before touching the expectations.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ai.vegaduta.ide.eclipse.settings.Environments;

class EnvironmentsTest {

    @Test
    @DisplayName("production resolves to the .ai hosts")
    void production() {
        assertEquals("https://api.vegaduta.ai", Environments.apiBase("production", ""));
        assertEquals("https://auth.vegaduta.ai", Environments.authBase("production", ""));
    }

    @Test
    @DisplayName("staging resolves to the .xyz hosts")
    void staging() {
        assertEquals("https://api.vegaduta.xyz", Environments.apiBase("staging", ""));
        assertEquals("https://auth.vegaduta.xyz", Environments.authBase("staging", ""));
    }

    @Test
    @DisplayName("an unknown persisted environment lands on staging, never production")
    void unknownEnvironmentIsSafe() {
        assertEquals("https://api.vegaduta.xyz", Environments.apiBase("banana", ""));
        assertEquals("https://api.vegaduta.xyz", Environments.apiBase(null, ""));
        assertEquals("https://auth.vegaduta.xyz", Environments.authBase("", ""));
    }

    @Test
    @DisplayName("custom uses the typed bases, trailing slashes removed")
    void custom() {
        assertEquals("https://api.example.com",
                Environments.apiBase("custom", "  https://api.example.com//  "));
        assertEquals("https://auth.example.com",
                Environments.authBase("custom", "https://auth.example.com/"));
    }

    @Test
    @DisplayName("a blank custom base falls back to staging, not to a broken URL")
    void blankCustom() {
        assertEquals("https://api.vegaduta.xyz", Environments.apiBase("custom", "   "));
        assertEquals("https://auth.vegaduta.xyz", Environments.authBase("custom", null));
    }

    @ParameterizedTest
    @DisplayName("RULE 0: a loopback custom base is refused for the API and the token issuer")
    @ValueSource(strings = {
        "http://localhost:8080",
        "https://localhost",
        "http://127.0.0.1:8180",
        "http://127.1.2.3",
        "http://[::1]:8080",
        "http://0.0.0.0:9000",
        "http://api.localhost:3000"
    })
    void loopbackPlatformBasesRefused(String url) {
        assertTrue(Environments.isLoopback(url), url);
        assertEquals("https://api.vegaduta.xyz", Environments.apiBase("custom", url));
        assertEquals("https://auth.vegaduta.xyz", Environments.authBase("custom", url));
    }

    @ParameterizedTest
    @DisplayName("real hosts are not mistaken for loopback")
    @ValueSource(strings = {
        "https://api.vegaduta.ai",
        "https://api.vegaduta.xyz",
        "https://vegaduta.example.com:8443",
        "https://127-0-0-1.example.com",
        "https://notlocalhost.example.com"
    })
    void realHostsPass(String url) {
        assertFalse(Environments.isLoopback(url), url);
        assertEquals(url, Environments.apiBase("custom", url));
    }

    @Test
    @DisplayName("a scheme-less loopback is still caught")
    void schemelessLoopback() {
        assertTrue(Environments.isLoopback("localhost:8080"));
        assertTrue(Environments.isLoopback("127.0.0.1"));
    }

    @Test
    @DisplayName("an unparseable base is passed through rather than silently rerouted")
    void unparseableIsNotRefused() {
        // Refusing it would quietly send a self-hosted install to staging. A
        // visible HTTP failure is the better outcome.
        assertFalse(Environments.isLoopback("http://exa mple.com"));
        assertFalse(Environments.isLoopback(null));
        assertFalse(Environments.isLoopback(""));
    }

    @Test
    @DisplayName("the OIDC base is realm-scoped and slash-safe")
    void openidBase() {
        assertEquals("https://auth.vegaduta.ai/realms/agentic-ai/protocol/openid-connect",
                Environments.openidBase("https://auth.vegaduta.ai/", "agentic-ai"));
        assertEquals("https://auth.vegaduta.xyz/realms/agentic-ai/protocol/openid-connect",
                Environments.openidBase("https://auth.vegaduta.xyz", "agentic-ai"));
    }

    @Test
    @DisplayName("trimTrailingSlashes is total")
    void trimming() {
        assertEquals("", Environments.trimTrailingSlashes(null));
        assertEquals("", Environments.trimTrailingSlashes("   "));
        assertEquals("", Environments.trimTrailingSlashes("///"));
        assertEquals("https://x", Environments.trimTrailingSlashes(" https://x/// "));
    }
}
