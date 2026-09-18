// Wire-contract tests for the SSE reader, ported case for case from
// clients/shared/test/sse.test.ts. Two regressions are guarded here and
// nowhere else:
//   - the slice(5) rule (a lone word-boundary token arrives as "data: " and
//     its payload is exactly one space);
//   - one backend chunk is one EVENT, so a multi-line payload's data lines are
//     rejoined with LF.
// If either of these tests is ever "fixed" by changing the expectation, read
// SseEventReader's header first - both bugs have shipped to production once.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.api.ApiException;
import ai.vegaduta.ide.eclipse.api.SseEventReader;

class SseEventReaderTest {

    /** Feeds lines, flushes, and returns the deltas that reached the consumer. */
    private static List<String> read(String... lines) {
        List<String> deltas = new ArrayList<>();
        SseEventReader reader = new SseEventReader(deltas::add);
        for (String line : lines) {
            reader.consumeLine(line);
        }
        reader.flush();
        return deltas;
    }

    @Test
    @DisplayName("strips exactly the 5-char data: prefix")
    void stripsExactlyFive() {
        assertEquals(List.of("hello"), read("data:hello", ""));
    }

    @Test
    @DisplayName("preserves a leading payload space (no 6th char eaten)")
    void preservesLeadingSpace() {
        assertEquals(List.of(" hello"), read("data: hello", ""));
    }

    @Test
    @DisplayName("delivers the lone-space chunk: 'data: ' => ' '")
    void loneSpaceChunk() {
        assertEquals(List.of(" "), read("data: ", ""));
    }

    @Test
    @DisplayName("forwards an empty payload as an empty string")
    void emptyPayload() {
        assertEquals(List.of(""), read("data:", ""));
    }

    @Test
    @DisplayName("rejoins one event's data lines with a newline")
    void rejoinsMultiLineEvent() {
        assertEquals(
                List.of("# Heading\n\n- one\n- two"),
                read("data:# Heading", "data:", "data:- one", "data:- two", ""));
    }

    @Test
    @DisplayName("keeps separate events separate")
    void separateEvents() {
        assertEquals(List.of("one", "two"), read("data:one", "", "data:two", ""));
    }

    @Test
    @DisplayName("flushes a final event the server never terminated")
    void flushesUnterminatedEvent() {
        assertEquals(List.of("tail"), read("data:tail"));
    }

    @Test
    @DisplayName("tolerates CRLF line endings")
    void tolerantOfCrlf() {
        assertEquals(List.of("a\nb"), read("data:a\r", "data:b\r", "\r"));
    }

    @Test
    @DisplayName("ignores non-data lines (comments, ids, events, wrong case, indented)")
    void ignoresOtherFields() {
        assertEquals(List.of(), read(": keep-alive", "id:1", "event:message", "Data:nope", "  data:x", ""));
    }

    @Test
    @DisplayName("throws ApiException carrying the sentinel's suffix message")
    void sentinelCarriesMessage() {
        ApiException error = assertThrows(ApiException.class,
                () -> read("data:" + SseEventReader.STREAM_ERROR_MARKER + "Provider exploded"));
        assertEquals(0, error.status());
        assertEquals("Provider exploded", error.getMessage());
    }

    @Test
    @DisplayName("throws a default message on a bare sentinel")
    void bareSentinelDefaultMessage() {
        ApiException error = assertThrows(ApiException.class,
                () -> read("data:" + SseEventReader.STREAM_ERROR_MARKER));
        assertEquals("The response was interrupted. Please try again.", error.getMessage());
    }

    @Test
    @DisplayName("a multi-line sentinel event is still an error, not text")
    void multiLineSentinel() {
        // The sentinel prefixes the WHOLE event payload, so a failure message
        // that itself has a newline in it must not slip through as content.
        ApiException error = assertThrows(ApiException.class,
                () -> read("data:" + SseEventReader.STREAM_ERROR_MARKER + "Upstream failed",
                        "data:after 3 retries", ""));
        assertEquals("Upstream failed\nafter 3 retries", error.getMessage());
    }

    @Test
    @DisplayName("an event of blank data lines survives as newlines, not as nothing")
    void blankDataLinesAreContent() {
        // Three empty payload lines are a two-newline gap in the reply - the
        // exact content the line-per-chunk bug used to delete.
        assertEquals(List.of("\n\n"), read("data:", "data:", "data:", ""));
    }

    @Test
    @DisplayName("consecutive blank wire lines do not emit empty events")
    void blankWireLinesAreNotEvents() {
        assertEquals(List.of("a"), read("", "", "data:a", "", "", ""));
    }

    @Test
    @DisplayName("a null line is ignored rather than crashing the stream")
    void nullLineIgnored() {
        List<String> deltas = new ArrayList<>();
        SseEventReader reader = new SseEventReader(deltas::add);
        reader.consumeLine(null);
        reader.consumeLine("data:ok");
        reader.flush();
        assertEquals(List.of("ok"), deltas);
    }
}
