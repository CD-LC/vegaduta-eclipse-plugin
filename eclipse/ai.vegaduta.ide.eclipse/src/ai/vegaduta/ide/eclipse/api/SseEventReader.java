// Exact port of createSseEventReader() in clients/shared/src/api/sse.ts, and
// of the wire contract AgentController.java writes. Two rules, both of which
// have already shipped as platform-wide incidents once and must not regress:
//
//  1. STRIP EXACTLY 5 CHARACTERS of "data:" - never a 6th. Spring's writer
//     emits `data:<content>` with NO separator space, so a chunk that is one
//     space (a lone word-boundary token) arrives as "data: " and its payload
//     IS that single space. Eating a 6th char silently deletes every one of
//     them and whole replies render with no spaces between words.
//
//  2. ONE BACKEND CHUNK IS ONE EVENT, NOT ONE LINE. SseEmitter's
//     SseEventBuilderImpl#data replaces every LF in a payload with
//     "LF data:" before writing, so a multi-line payload arrives as SEVERAL
//     `data:` lines terminated by one blank line. The lines of an event must
//     be accumulated and rejoined with LF. Forwarding each line separately
//     deletes every newline in the reply - catastrophic on the buffered
//     reasoning depths (CAREFUL / RIGOROUS / PLANNED), where the WHOLE answer
//     is a single multi-line event. This is the bug fixed in commit 81416d12;
//     note that clients/jetbrains's ApiClient.streamChat still reads
//     line-per-chunk, so do NOT copy that file's loop.
//
// A mid-stream provider failure (the HTTP status was already committed as
// 200) arrives as one final event prefixed with STREAM_ERROR_MARKER: it must
// surface as an error, never as literal streamed text.

package ai.vegaduta.ide.eclipse.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SseEventReader {

    /** Mirror of AgentController.STREAM_ERROR_MARKER. */
    public static final String STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

    private static final String DEFAULT_STREAM_ERROR = "The response was interrupted. Please try again.";

    /** The exact prefix length rule 1 above is about. Named so a future edit
     * has to argue with the constant rather than change a magic number. */
    private static final int DATA_PREFIX_LENGTH = "data:".length();

    private final Consumer<String> onChunk;
    private final List<String> dataLines = new ArrayList<>();

    public SseEventReader(Consumer<String> onChunk) {
        this.onChunk = onChunk;
    }

    /**
     * Feeds one complete wire line, WITHOUT its terminating newline.
     *
     * @throws ApiException when the completed event is the error sentinel.
     */
    public void consumeLine(String rawLine) {
        if (rawLine == null) {
            return;
        }
        // CR tolerance: the SSE spec lets a line end CR, LF or CRLF. Spring
        // writes bare LF, so this is defensive - but without it a CRLF stream
        // would never produce an empty line and no event would ever dispatch.
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        if (line.isEmpty()) {
            dispatch();
            return;
        }
        if (line.startsWith("data:")) {
            dataLines.add(line.substring(DATA_PREFIX_LENGTH));
        }
        // Every other field (event:, id:, retry:) and every comment (": ...")
        // is ignored, exactly as the shared reader ignores them.
    }

    /**
     * Emits a trailing event the server never terminated with a blank line.
     *
     * @throws ApiException when that event is the error sentinel.
     */
    public void flush() {
        dispatch();
    }

    private void dispatch() {
        if (dataLines.isEmpty()) {
            return;
        }
        String payload = String.join("\n", dataLines);
        dataLines.clear();
        if (payload.startsWith(STREAM_ERROR_MARKER)) {
            String detail = payload.substring(STREAM_ERROR_MARKER.length());
            throw new ApiException(0, detail.isBlank() ? DEFAULT_STREAM_ERROR : detail);
        }
        onChunk.accept(payload);
    }
}
