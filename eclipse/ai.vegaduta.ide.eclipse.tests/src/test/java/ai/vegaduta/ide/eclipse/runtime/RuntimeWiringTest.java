// RuntimeWiring: how the bundled server is written into (and cleared from)
// the "Local inference server" settings, when hostLocalEngine is claimed, and
// init.edgeSettings.

package ai.vegaduta.ide.eclipse.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class RuntimeWiringTest {

    /** An in-memory stand-in for VegadutaSettings.localServerSettings(). */
    private static final class FakeSettings implements RuntimeWiring.LocalServerSettings {
        String baseUrl;
        String model;
        String bundled;

        @Override
        public String localServerBaseUrl() {
            return baseUrl;
        }

        @Override
        public String localServerModel() {
            return model;
        }

        @Override
        public void setLocalServer(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }

        @Override
        public String bundledBaseUrl() {
            return bundled;
        }

        @Override
        public void setBundledBaseUrl(String baseUrl) {
            this.bundled = baseUrl;
        }
    }

    @Test
    @DisplayName("running: Base URL and Model become the bundled server's, and the URL is remembered as ours")
    void onRunning() {
        FakeSettings settings = new FakeSettings();
        settings.baseUrl = "http://127.0.0.1:11434";
        settings.model = "llama3";
        RuntimeWiring.onRunning(settings, "http://127.0.0.1:50123", "qwen2.5-coder-1.5b");
        assertEquals("http://127.0.0.1:50123", settings.baseUrl);
        assertEquals("qwen2.5-coder-1.5b", settings.model);
        assertEquals("http://127.0.0.1:50123", settings.bundled);
    }

    @Test
    @DisplayName("stopped: our own entry is cleared")
    void onStoppedClearsOurs() {
        FakeSettings settings = new FakeSettings();
        RuntimeWiring.onRunning(settings, "http://127.0.0.1:50123", "qwen2.5-coder-1.5b");
        assertTrue(RuntimeWiring.onStopped(settings));
        assertNull(settings.baseUrl);
        assertNull(settings.model);
        assertNull(settings.bundled);
    }

    @Test
    @DisplayName("stopped: a URL the person typed since is kept; only the marker goes")
    void onStoppedKeepsTheirs() {
        FakeSettings settings = new FakeSettings();
        RuntimeWiring.onRunning(settings, "http://127.0.0.1:50123", "qwen2.5-coder-1.5b");
        settings.setLocalServer("http://127.0.0.1:11434", "llama3"); // typed in the preference page
        assertFalse(RuntimeWiring.onStopped(settings));
        assertEquals("http://127.0.0.1:11434", settings.baseUrl);
        assertEquals("llama3", settings.model);
        assertNull(settings.bundled);
    }

    @Test
    @DisplayName("stopped with nothing of ours configured changes nothing")
    void onStoppedNoop() {
        FakeSettings settings = new FakeSettings();
        settings.baseUrl = "http://127.0.0.1:1234";
        assertFalse(RuntimeWiring.onStopped(settings));
        assertEquals("http://127.0.0.1:1234", settings.baseUrl);
    }

    @Test
    @DisplayName("hostLocalEngine only while the configured server IS the running bundled one")
    void hostLocalEngine() {
        RuntimeStatus running = new RuntimeStatus(RuntimeStatus.State.RUNNING, "m", null, null,
                "http://127.0.0.1:50123");
        assertTrue(RuntimeWiring.hostLocalEngine("http://127.0.0.1:50123", running));
        assertTrue(RuntimeWiring.hostLocalEngine("http://127.0.0.1:50123/", running));
        assertFalse(RuntimeWiring.hostLocalEngine("http://127.0.0.1:11434", running), "the person's own Ollama");
        assertFalse(RuntimeWiring.hostLocalEngine(null, running));
        assertFalse(RuntimeWiring.hostLocalEngine("http://127.0.0.1:50123", null));
        for (RuntimeStatus.State state : RuntimeStatus.State.values()) {
            if (state != RuntimeStatus.State.RUNNING) {
                assertFalse(RuntimeWiring.hostLocalEngine("http://127.0.0.1:50123",
                        new RuntimeStatus(state, "m", null, null, "http://127.0.0.1:50123")), state.wire());
            }
        }
    }

    @Test
    @DisplayName("edgeSettings carries the address and model; unset is an empty string, which removes the key")
    void edgeSettings() {
        assertEquals("{\"edge.ollamaBaseUrl\":\"http://127.0.0.1:50123\",\"edge.ollamaModel\":\"m\"}",
                Json.write(RuntimeWiring.edgeSettings("http://127.0.0.1:50123", "m")));
        assertEquals("{\"edge.ollamaBaseUrl\":\"\",\"edge.ollamaModel\":\"\"}",
                Json.write(RuntimeWiring.edgeSettings(null, null)));
    }
}
