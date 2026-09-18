// One snapshot of the bundled runtime's state, and its wire form: the
// runtime.status message of clients/shared/src/webview/protocol.ts
// (RuntimeStatus + RuntimeModel), field for field.
//
// Pure Java, unit-tested in RuntimeStatusTest.

package ai.vegaduta.ide.eclipse.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import ai.vegaduta.ide.eclipse.json.Json;

public record RuntimeStatus(State state, String modelId, Double progress, String detail, String baseUrl) {

    /** protocol.ts RuntimeStatus["state"], in the same order. */
    public enum State {
        ABSENT, DOWNLOADING, STARTING, RUNNING, STOPPED, ERROR;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static RuntimeStatus of(State state) {
        return new RuntimeStatus(state, null, null, null, null);
    }

    public boolean busy() {
        return state == State.DOWNLOADING || state == State.STARTING;
    }

    /**
     * The runtime.status message. {@code installed} answers "is this model's
     * file downloaded and verified here?" for each catalog entry. Optional
     * fields that are unset are left out, never sent as null.
     */
    public Map<String, Object> toMessage(List<RuntimeCatalog.Model> catalog,
            Predicate<RuntimeCatalog.Model> installed) {
        Map<String, Object> message = Json.object();
        message.put("type", "runtime.status");
        message.put("state", state.wire());
        if (modelId != null) {
            message.put("modelId", modelId);
        }
        if (progress != null) {
            message.put("progress", Math.max(0d, Math.min(1d, progress)));
        }
        if (detail != null && !detail.isBlank()) {
            message.put("detail", detail);
        }
        if (baseUrl != null && state == State.RUNNING) {
            message.put("baseUrl", baseUrl);
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (RuntimeCatalog.Model model : catalog) {
            Map<String, Object> entry = Json.object();
            entry.put("id", model.id());
            entry.put("displayName", model.displayName());
            entry.put("sizeBytes", model.file().sizeBytes());
            entry.put("installed", installed.test(model));
            entry.put("recommended", model.recommended());
            models.add(entry);
        }
        message.put("models", models);
        return message;
    }

    /** One line for the preference page and the status label. */
    public String describe() {
        String what = modelId == null ? "" : " (" + modelId + ")";
        return switch (state) {
            case ABSENT -> "Not installed";
            case DOWNLOADING -> (detail != null ? detail : "Downloading") + what;
            case STARTING -> "Starting" + what + (detail != null ? " - " + detail : "");
            case RUNNING -> "Running" + what + (baseUrl != null ? " at " + baseUrl : "");
            case STOPPED -> "Stopped" + what;
            case ERROR -> "Error" + what + (detail != null ? ": " + firstLine(detail) : "");
        };
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }
}
