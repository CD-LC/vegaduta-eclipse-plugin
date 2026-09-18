// RuntimeStatus.toMessage: the runtime.status wire shape, field for field
// with protocol.ts RuntimeStatus + RuntimeModel.

package ai.vegaduta.ide.eclipse.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class RuntimeStatusTest {

    @Test
    @DisplayName("downloading: type, state, modelId, progress, detail and the full model list")
    @SuppressWarnings("unchecked")
    void downloadingShape() {
        RuntimeStatus status = new RuntimeStatus(RuntimeStatus.State.DOWNLOADING, "qwen2.5-coder-1.5b", 0.37,
                "Downloading the model - 412 MB of 1.1 GB", null);
        Map<String, Object> message = status.toMessage(RuntimeCatalog.MODELS,
                model -> model.id().equals("qwen2.5-coder-3b"));
        assertEquals(List.of("type", "state", "modelId", "progress", "detail", "models"),
                List.copyOf(message.keySet()));
        assertEquals("runtime.status", message.get("type"));
        assertEquals("downloading", message.get("state"));
        assertEquals(0.37, message.get("progress"));
        List<Map<String, Object>> models = (List<Map<String, Object>>) message.get("models");
        assertEquals(3, models.size());
        assertEquals(List.of("id", "displayName", "sizeBytes", "installed", "recommended"),
                List.copyOf(models.get(0).keySet()));
        assertEquals("{\"id\":\"qwen2.5-coder-1.5b\",\"displayName\":\"Qwen2.5 Coder 1.5B, fast\","
                + "\"sizeBytes\":1117320768,\"installed\":false,\"recommended\":true}", Json.write(models.get(0)));
        assertEquals(Boolean.TRUE, models.get(1).get("installed"));
        assertEquals(Boolean.FALSE, models.get(2).get("recommended"));
    }

    @Test
    @DisplayName("running carries baseUrl; unset optionals are omitted, never null")
    void runningShape() {
        RuntimeStatus running = new RuntimeStatus(RuntimeStatus.State.RUNNING, "qwen2.5-coder-7b", null, null,
                "http://127.0.0.1:50123");
        String json = Json.write(running.toMessage(List.of(), m -> true));
        assertEquals("{\"type\":\"runtime.status\",\"state\":\"running\",\"modelId\":\"qwen2.5-coder-7b\","
                + "\"baseUrl\":\"http://127.0.0.1:50123\",\"models\":[]}", json);

        String absent = Json.write(RuntimeStatus.of(RuntimeStatus.State.ABSENT).toMessage(List.of(), m -> false));
        assertEquals("{\"type\":\"runtime.status\",\"state\":\"absent\",\"models\":[]}", absent);
    }

    @Test
    @DisplayName("baseUrl is only reported while running, and progress is clamped to 0..1")
    void guards() {
        Map<String, Object> stopped = new RuntimeStatus(RuntimeStatus.State.STOPPED, "m", 1.7, "", "http://x")
                .toMessage(List.of(), m -> false);
        assertFalse(stopped.containsKey("baseUrl"));
        assertFalse(stopped.containsKey("detail"));
        assertEquals(1.0, stopped.get("progress"));
    }

    @Test
    @DisplayName("every state's wire name is the protocol's")
    void wireNames() {
        assertEquals(List.of("absent", "downloading", "starting", "running", "stopped", "error"),
                java.util.Arrays.stream(RuntimeStatus.State.values()).map(RuntimeStatus.State::wire).toList());
        assertTrue(RuntimeStatus.of(RuntimeStatus.State.STARTING).busy());
        assertFalse(RuntimeStatus.of(RuntimeStatus.State.RUNNING).busy());
    }

    @Test
    @DisplayName("describe() gives the preference page one honest line, first error line only")
    void describe() {
        assertEquals("Not installed", RuntimeStatus.of(RuntimeStatus.State.ABSENT).describe());
        assertEquals("Running (m) at http://127.0.0.1:1",
                new RuntimeStatus(RuntimeStatus.State.RUNNING, "m", null, null, "http://127.0.0.1:1").describe());
        assertEquals("Error (m): server exited",
                new RuntimeStatus(RuntimeStatus.State.ERROR, "m", null, "server exited\nlog line", null).describe());
    }
}
