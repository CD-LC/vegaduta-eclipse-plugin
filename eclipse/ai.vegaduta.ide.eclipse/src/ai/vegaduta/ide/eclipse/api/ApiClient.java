// Blocking HTTP client for the platform API over java.net.http. Call only
// from background threads (a Job, never the SWT UI thread). Mirrors
// clients/shared/src/api/{client,sse}.ts and the JetBrains twin:
//   - Authorization bearer from TokenStore; on 401 refresh-once-and-retry;
//   - SSE chat read BYTE-WISE and framed by SseEventReader, so one backend
//     chunk is one EVENT (see that class for why a line-per-chunk loop is a
//     shipped-incident bug);
//   - vmcp_ API-key mode transparently reroutes to the blocking /api/dev/v1
//     surface - streaming stays JWT-only by design, so an API-key chat is
//     delivered as one chunk.
//
// Token rule: this class is the ONLY place a bearer token is used, and it runs
// host-side. Nothing here is ever handed to the webview.

package ai.vegaduta.ide.eclipse.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import ai.vegaduta.ide.eclipse.auth.TokenStore;
import ai.vegaduta.ide.eclipse.json.Json;
import ai.vegaduta.ide.eclipse.settings.VegadutaSettings;

public final class ApiClient {

    private static final ApiClient INSTANCE = new ApiClient();

    public static ApiClient get() {
        return INSTANCE;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ApiClient() {
    }

    private static String apiBase() {
        return VegadutaSettings.apiBase();
    }

    /**
     * vmcp_ keys only authenticate the /api/dev/v1 surface; the path shapes are
     * otherwise identical, so API-key mode is a prefix swap. Package-visible so
     * it can be exercised without a live server.
     */
    static String surfacePath(String path, boolean signedIn, boolean hasApiKey) {
        if (!signedIn && hasApiKey) {
            return path.replaceFirst("/api/", "/api/dev/v1/");
        }
        return path;
    }

    private String surfacePath(String path) {
        TokenStore tokens = TokenStore.get();
        return surfacePath(path, tokens.isSignedIn(), tokens.apiKey() != null);
    }

    private HttpRequest.Builder builder(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase() + path))
                .timeout(Duration.ofSeconds(120));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    /** JSON request with the shared refresh-once-on-401 rule. */
    private HttpResponse<String> send(String path, String method, String body) {
        TokenStore tokens = TokenStore.get();
        HttpResponse<String> response = attempt(path, method, body, tokens.bearerToken());
        if (response.statusCode() == 401) {
            String refreshed = tokens.refreshBlocking();
            if (refreshed != null) {
                response = attempt(path, method, body, refreshed);
            }
        }
        return response;
    }

    private HttpResponse<String> attempt(String path, String method, String body, String token) {
        HttpRequest.Builder b = builder(path, token);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(0, "Could not reach " + apiBase() + " (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "The request was interrupted.");
        }
    }

    private String okBody(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new ApiException(response.statusCode(), extractError(response.body(), response.statusCode()));
        }
        return response.body();
    }

    /**
     * Surfaces core's GlobalExceptionHandler {@code { error }} body verbatim.
     * Package-visible so the fallback wording is testable.
     */
    static String extractError(String body, int status) {
        if (body != null && !body.isBlank()) {
            Map<String, Object> o = Json.parseObjectOrEmpty(body);
            String message = Json.str(o, "error");
            if (message == null) {
                message = Json.str(o, "message");
            }
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        if (status == 401) {
            return "Not signed in. Sign in from the VegaDuta view or Window > Preferences > VegaDuta.";
        }
        return "HTTP " + status;
    }

    // --- listings ------------------------------------------------------------

    public List<Models.AgentSummary> listAgents() {
        List<Models.AgentSummary> result = new ArrayList<>();
        for (Object item : Json.parseArray(okBody(send(surfacePath("/api/agents"), "GET", null)))) {
            if (item instanceof Map<?, ?>) {
                result.add(Models.AgentSummary.from(castObject(item)));
            }
        }
        return result;
    }

    public List<Models.WorkflowSummary> listWorkflows() {
        List<Models.WorkflowSummary> result = new ArrayList<>();
        for (Object item : Json.parseArray(okBody(send(surfacePath("/api/workflows"), "GET", null)))) {
            if (item instanceof Map<?, ?>) {
                result.add(Models.WorkflowSummary.from(castObject(item)));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        return (Map<String, Object>) value;
    }

    // --- workflows -----------------------------------------------------------

    public Models.WorkflowRun runWorkflow(String workflowId, String input) {
        Map<String, Object> body = Json.object();
        body.put("input", input);
        return Models.WorkflowRun.from(Json.parseObject(
                okBody(send(surfacePath("/api/workflows/" + workflowId + "/run"), "POST", Json.write(body)))));
    }

    public Models.WorkflowRun getWorkflowRun(String workflowId, String runId) {
        return Models.WorkflowRun.from(Json.parseObject(okBody(
                send(surfacePath("/api/workflows/" + workflowId + "/runs/" + runId), "GET", null))));
    }

    // --- chat ----------------------------------------------------------------

    /** What a completed chat turn reports back to the caller. */
    public record ChatResult(String sessionId) {
    }

    /**
     * Streams POST /api/agents/{id}/chat, host-side.
     *
     * Framing is delegated to SseEventReader - read its header before touching
     * this method. The stream is decoded from BYTES, not from
     * BodyHandlers.ofLines(), for two reasons: a line handler cannot tell a
     * blank separator line from end-of-stream cleanly, and a multi-byte
     * character split across two network reads must survive (the shared reader
     * uses a streaming TextDecoder for exactly this).
     *
     * @param cancelled set by the caller to stop; the socket is closed so a
     *        blocked read unblocks promptly instead of only on the next chunk.
     * @throws ApiException on an HTTP failure or the mid-stream sentinel.
     */
    public ChatResult streamChat(String agentId, String message, String sessionId,
            AtomicBoolean cancelled, Consumer<String> onChunk) {

        TokenStore tokens = TokenStore.get();
        if (!tokens.isSignedIn() && tokens.apiKey() != null) {
            // dev/v1 has no streaming surface - deliver the blocking reply as
            // one chunk rather than pretending to stream.
            Map<String, Object> body = Json.object();
            body.put("message", message);
            if (sessionId != null && !sessionId.isBlank()) {
                body.put("sessionId", sessionId);
            }
            Map<String, Object> reply = Json.parseObject(
                    okBody(send("/api/dev/v1/agents/" + agentId + "/chat", "POST", Json.write(body))));
            if (!cancelled.get()) {
                onChunk.accept(Json.str(reply, "reply", ""));
            }
            String returned = Json.str(reply, "sessionId");
            return new ChatResult(returned != null ? returned : sessionId);
        }

        Map<String, Object> payload = Json.object();
        if (sessionId != null && !sessionId.isBlank()) {
            payload.put("sessionId", sessionId);
        }
        payload.put("message", message);
        String json = Json.write(payload);

        HttpResponse<InputStream> response = attemptStream(agentId, json, tokens.bearerToken());
        if (response.statusCode() == 401) {
            String refreshed = tokens.refreshBlocking();
            if (refreshed != null) {
                closeQuietly(response.body());
                response = attemptStream(agentId, json, refreshed);
            }
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            String body = drain(response.body());
            throw new ApiException(response.statusCode(), extractError(body, response.statusCode()));
        }

        String resolvedSession = response.headers().firstValue("X-Session-Id").orElse(sessionId);
        InputStream stream = response.body();
        // readSse only checks `cancelled` BETWEEN reads, but read() blocks on
        // the socket - if the server goes quiet mid-turn (during a tool call,
        // say), setting `cancelled` alone would do nothing until the next chunk
        // or keep-alive arrives. Bridge it to actually closing the stream so a
        // blocked read unblocks promptly. Mirrors the JetBrains watcher thread.
        Thread watcher = new Thread(() -> {
            try {
                while (!cancelled.get()) {
                    Thread.sleep(200);
                }
                closeQuietly(stream);
            } catch (InterruptedException e) {
                // The read finished normally - nothing to close.
            }
        }, "vegaduta-sse-cancel");
        watcher.setDaemon(true);
        watcher.start();
        try {
            readSse(stream, cancelled, onChunk);
        } finally {
            watcher.interrupt();
        }
        return new ChatResult(resolvedSession);
    }

    private HttpResponse<InputStream> attemptStream(String agentId, String json, String token) {
        HttpRequest request = builder("/api/agents/" + agentId + "/chat", token)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ApiException(0, "Could not reach " + apiBase() + " (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "The request was interrupted.");
        }
    }

    /**
     * Decodes the SSE body and feeds whole lines to SseEventReader.
     *
     * Package-visible and taking a plain InputStream so the framing path -
     * chunk boundaries, split multi-byte characters, an unterminated final
     * event - is testable without a server (SseStreamTest).
     */
    static void readSse(InputStream body, AtomicBoolean cancelled, Consumer<String> onChunk) {
        SseEventReader events = new SseEventReader(onChunk);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        byte[] buffer = new byte[8192];
        // Bytes of an incomplete multi-byte character carried to the next read.
        // Sized one read PLUS a character's worth, so a leftover byte can never
        // make the next full read overflow it.
        ByteBuffer pending = ByteBuffer.allocate(buffer.length + 8);
        StringBuilder line = new StringBuilder();
        try (InputStream in = body) {
            for (;;) {
                if (cancelled.get()) {
                    return;
                }
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                CharBuffer chars = decode(decoder, pending, buffer, read, false);
                emitLines(chars, line, events);
            }
            // Flush the decoder, then the final line, then the final event -
            // a server can end a stream without a trailing newline and without
            // the blank line that terminates the last event.
            CharBuffer tail = decode(decoder, pending, buffer, 0, true);
            emitLines(tail, line, events);
            if (line.length() > 0) {
                events.consumeLine(line.toString());
                line.setLength(0);
            }
            events.flush();
        } catch (IOException e) {
            // A cancellation-triggered close surfaces here as an IOException
            // from the blocked read - that is the clean "user pressed Stop"
            // path, not a chat error.
            if (cancelled.get()) {
                return;
            }
            throw new ApiException(0, "The response stream failed (" + e.getMessage() + ")");
        }
    }

    private static CharBuffer decode(CharsetDecoder decoder, ByteBuffer pending,
            byte[] buffer, int read, boolean endOfInput) {
        pending.put(buffer, 0, read);
        pending.flip();
        CharBuffer out = CharBuffer.allocate(buffer.length + 16);
        decoder.decode(pending, out, endOfInput);
        if (endOfInput) {
            decoder.flush(out);
        }
        pending.compact();
        out.flip();
        return out;
    }

    /** Splits on LF; the CR of a CRLF pair is left on the line for
     * SseEventReader, which strips it (and is tested for that). */
    private static void emitLines(CharBuffer chars, StringBuilder line, SseEventReader events) {
        while (chars.hasRemaining()) {
            char c = chars.get();
            if (c == '\n') {
                events.consumeLine(line.toString());
                line.setLength(0);
            } else {
                line.append(c);
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Nothing useful to do - the connection is being abandoned anyway.
        }
    }

    private static String drain(InputStream stream) {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // --- sandbox -------------------------------------------------------------

    /**
     * POST /api/sdlc/sandbox/run-code - stateless, disposable-sandbox
     * execution, gated server-side to ROLE_tenant-admin / ROLE_agent-builder.
     * JWT surface only: dev/v1 has no /api/sdlc routes, so an API-key-only user
     * gets a clear message instead of a confusing 404.
     */
    public Models.RunCodeResult runCode(String code, String language) {
        if (!TokenStore.get().isSignedIn()) {
            throw new ApiException(0,
                    "Running code in a sandbox needs a full sign-in (not an API key) - sign in from the"
                            + " VegaDuta view.");
        }
        Map<String, Object> body = Json.object();
        body.put("code", code);
        body.put("language", language);
        return Models.RunCodeResult.from(
                Json.parseObject(okBody(send("/api/sdlc/sandbox/run-code", "POST", Json.write(body)))));
    }

    // --- knowledge -----------------------------------------------------------

    /**
     * POST /api/knowledge/search, mapped to protocol KnowledgeHit maps.
     *
     * JWT surface only: a vmcp_ key authenticates /api/dev/v1, which has no
     * knowledge routes, so an API-key-only (or signed-out) caller gets a 401
     * ApiException up front - Knowledge.reasonFor turns that into
     * "signed-out" - instead of a request that could only fail.
     */
    public List<Map<String, Object>> searchKnowledge(String query, int topK, List<String> collectionIds) {
        if (!TokenStore.get().isSignedIn()) {
            throw new ApiException(401,
                    "Knowledge search needs a full sign-in (not an API key) - sign in from the VegaDuta view.");
        }
        String body = Json.write(Knowledge.requestBody(query, topK, collectionIds));
        return Knowledge.toHits(Json.parseArray(okBody(send("/api/knowledge/search", "POST", body))));
    }
}
