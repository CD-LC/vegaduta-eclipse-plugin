// Stream-level tests for the chat reader: the byte-decoding and line-splitting
// that sits between the socket and SseEventReader. Ported from the
// streamAgentChat block of clients/shared/test/sse.test.ts, which covers the
// three things a naive reader gets wrong - events split across network reads,
// a multi-byte character split across reads, and a final line with no
// trailing newline.
//
// Same package as ApiClient so the package-private seam (readSse, extractError,
// surfacePath) is reachable without opening it up in production code.

package ai.vegaduta.ide.eclipse.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiClientStreamTest {

    /** An InputStream that hands out one prepared chunk per read(), so the
     * chunk boundaries the tests care about are the ones the reader sees. */
    private static InputStream chunked(byte[]... chunks) {
        List<InputStream> streams = new ArrayList<>();
        for (byte[] chunk : chunks) {
            streams.add(new ByteArrayInputStream(chunk) {
                @Override
                public int read(byte[] b, int off, int len) {
                    // Never spans into the next chunk: SequenceInputStream only
                    // moves on once this one reports EOF.
                    return super.read(b, off, Math.min(len, available()));
                }
            });
        }
        return new SequenceInputStream(Collections.enumeration(streams));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> read(InputStream stream) {
        List<String> deltas = new ArrayList<>();
        ApiClient.readSse(stream, new AtomicBoolean(false), deltas::add);
        return deltas;
    }

    @Test
    @DisplayName("buffers events across chunk boundaries")
    void buffersAcrossChunks() {
        assertEquals(
                List.of("Hello", " ", "world"),
                read(chunked(utf8("data:Hel"), utf8("lo\n\ndata: \n\nda"), utf8("ta:world\n\n"))));
    }

    @Test
    @DisplayName("emits a final line that has no trailing newline")
    void finalLineWithoutNewline() {
        assertEquals(List.of("first", "tail"), read(chunked(utf8("data:first\n\n"), utf8("data:tail"))));
    }

    @Test
    @DisplayName("decodes a multi-byte character split across reads")
    void multiByteSplitAcrossReads() {
        byte[] bytes = utf8("data:héllo\n\n");
        int cut = 7; // splits the two-byte e-acute
        byte[] head = new byte[cut];
        byte[] tail = new byte[bytes.length - cut];
        System.arraycopy(bytes, 0, head, 0, cut);
        System.arraycopy(bytes, cut, tail, 0, tail.length);
        assertEquals(List.of("héllo"), read(chunked(head, tail)));
    }

    @Test
    @DisplayName("a multi-line event survives being split mid-payload")
    void multiLineEventSplitMidPayload() {
        // The whole answer as ONE event, delivered in three network reads.
        assertEquals(
                List.of("line one\nline two\nline three"),
                read(chunked(utf8("data:line one\ndata:li"), utf8("ne two\ndata:line thr"), utf8("ee\n\n"))));
    }

    @Test
    @DisplayName("surfaces the mid-stream sentinel as an ApiException after earlier chunks")
    void midStreamSentinel() {
        List<String> deltas = new ArrayList<>();
        InputStream stream = chunked(
                utf8("data:partial\n\n"),
                utf8("data:" + SseEventReader.STREAM_ERROR_MARKER + "Upstream provider timed out\n\n"));
        ApiException error = assertThrows(ApiException.class,
                () -> ApiClient.readSse(stream, new AtomicBoolean(false), deltas::add));
        assertEquals("Upstream provider timed out", error.getMessage());
        assertEquals(List.of("partial"), deltas);
    }

    @Test
    @DisplayName("stops without error when cancelled")
    void cancellationIsNotAnError() {
        List<String> deltas = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        ApiClient.readSse(chunked(utf8("data:never\n\n")), cancelled, deltas::add);
        assertEquals(List.of(), deltas);
    }

    @Test
    @DisplayName("an IO failure mid-stream becomes an ApiException, not a leaked IOException")
    void ioFailureIsTyped() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("socket reset");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("socket reset");
            }
        };
        ApiException error = assertThrows(ApiException.class,
                () -> ApiClient.readSse(failing, new AtomicBoolean(false), delta -> { }));
        assertEquals(0, error.status());
        assertTrue(error.getMessage().contains("socket reset"), error.getMessage());
    }

    // --- the small pure helpers on ApiClient --------------------------------

    @Test
    @DisplayName("API-key mode reroutes to the dev/v1 surface, JWT mode does not")
    void surfacePathSwap() {
        assertEquals("/api/dev/v1/agents", ApiClient.surfacePath("/api/agents", false, true));
        assertEquals("/api/dev/v1/workflows/1/run",
                ApiClient.surfacePath("/api/workflows/1/run", false, true));
        assertEquals("/api/agents", ApiClient.surfacePath("/api/agents", true, true));
        assertEquals("/api/agents", ApiClient.surfacePath("/api/agents", false, false));
    }

    @Test
    @DisplayName("only the FIRST /api/ is rewritten")
    void surfacePathRewritesOnlyTheFirstSegment() {
        assertEquals("/api/dev/v1/agents/api/thing",
                ApiClient.surfacePath("/api/agents/api/thing", false, true));
    }

    @Test
    @DisplayName("the server's own error text wins over the status line")
    void extractErrorPrefersServerMessage() {
        assertEquals("Agent not found", ApiClient.extractError("{\"error\":\"Agent not found\"}", 404));
        assertEquals("boom", ApiClient.extractError("{\"message\":\"boom\"}", 500));
    }

    @Test
    @DisplayName("a non-JSON or empty error body falls back to the status")
    void extractErrorFallsBack() {
        assertEquals("HTTP 502", ApiClient.extractError("<html>gateway</html>", 502));
        assertEquals("HTTP 500", ApiClient.extractError("", 500));
        assertEquals("HTTP 500", ApiClient.extractError(null, 500));
        assertTrue(ApiClient.extractError(null, 401).startsWith("Not signed in"));
    }
}
