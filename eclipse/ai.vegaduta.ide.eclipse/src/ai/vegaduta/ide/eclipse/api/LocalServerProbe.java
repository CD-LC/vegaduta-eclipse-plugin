// Host-side probe of the user's own OpenAI-compatible inference server
// (Ollama / LM Studio / llama.cpp): GET {base}/v1/models, the same call
// clients/shared/src/edge/ollamaEngine.ts makes.
//
// This is the ONLY place in the plugin where a loopback address is legitimate,
// and RULE 0 does not reach it: the address is the user's own process on their
// own machine, typed by them, with no default. See Environments' header.
//
// It exists because the status readout drawn inside the chat page cannot be
// trusted on this host - the page runs from a file:// origin, so a browser
// fetch at the local server is subject to CORS with an opaque Origin, and a
// CORS refusal is indistinguishable in-page from "no server". Asking from Java
// has no such problem, so the label under the browser reports what the HOST
// found. Typed outcomes, never an exception, matching the edge layer's posture.

package ai.vegaduta.ide.eclipse.api;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import ai.vegaduta.ide.eclipse.json.Json;

public final class LocalServerProbe {

    public enum Result {
        /** The server answered and lists at least one model. */
        OK,
        /** The server answered, but its model list is empty. */
        NO_MODELS,
        /** The server answered with an HTTP error (auth, wrong path, ...). */
        REFUSED,
        /** Nothing answered at that address. */
        UNREACHABLE,
        /** No local server URL is configured - the default state. */
        NOT_CONFIGURED
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private LocalServerProbe() {
    }

    /** Probes {@code baseUrl}, or reports NOT_CONFIGURED when it is null. */
    public static Result probe(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Result.NOT_CONFIGURED;
        }
        HttpResponse<String> response;
        try {
            response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            return Result.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.UNREACHABLE;
        } catch (Exception e) {
            // Includes an unparseable URL and every other transport failure.
            return Result.UNREACHABLE;
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            return Result.REFUSED;
        }
        return hasModel(response.body()) ? Result.OK : Result.NO_MODELS;
    }

    /** True when an OpenAI-compatible /v1/models body lists at least one entry.
     * The two shapes in the wild are
     * {"data":[...]} (OpenAI/LM Studio) and a bare array. */
    public static boolean hasModel(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof List<?> list) {
                return !list.isEmpty();
            }
            if (parsed instanceof Map<?, ?>) {
                Object data = ((Map<?, ?>) parsed).get("data");
                return data instanceof List<?> list && !list.isEmpty();
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The sentence shown under the browser for an outcome. Kept here so the
     * wording is one thing, testable, and states only what was established. */
    public static String describe(Result result, String baseUrl) {
        return switch (result) {
            case OK -> "On-device engine: " + baseUrl + " is answering and lists a model";
            case NO_MODELS -> "On-device engine: off - " + baseUrl + " answered, but lists no models";
            case REFUSED -> "On-device engine: off - " + baseUrl + " refused the request (see the error log)";
            case UNREACHABLE -> "On-device engine: off - no local inference server answering at " + baseUrl;
            case NOT_CONFIGURED ->
                "On-device engine: off - set a local inference server URL in Window > Preferences > VegaDuta";
        };
    }
}
