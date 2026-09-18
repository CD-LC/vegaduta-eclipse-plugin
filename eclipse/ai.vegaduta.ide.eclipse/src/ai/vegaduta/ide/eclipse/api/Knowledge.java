// knowledge.search -> POST /api/knowledge/search -> knowledge.result.
//
// Pure (no SWT, no OSGi, no network): the request body, the mapping of core's
// SearchResultResponse onto protocol.ts's KnowledgeHit, and the failure
// reasons. ApiClient.searchKnowledge does the HTTP; WebviewBridge wires it.
//
// Server shape (core/knowledge KnowledgeController#search):
//   request  SearchRequest(@NotBlank query, Integer topK, Set<UUID> collectionIds)
//   response List<SearchResultResponse(UUID id, String source, int chunkIndex,
//            String content, UUID collectionId, double distance, UUID documentId)>
// KnowledgeHit keeps id/source/content/collectionId/documentId/distance and
// drops chunkIndex, exactly as protocol.ts declares it.

package ai.vegaduta.ide.eclipse.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.vegaduta.ide.eclipse.json.Json;

public final class Knowledge {

    private Knowledge() {
    }

    /** Core's own default when topK is absent. */
    public static final int DEFAULT_TOP_K = 5;
    /** Upper bound the host applies before sending: a knowledge answer is an
     * attachment, and 20 chunks is already more than a prompt should carry. */
    public static final int MAX_TOP_K = 20;
    /** Longest query sent. The endpoint embeds it; a pasted file is not a
     * search query. */
    public static final int MAX_QUERY_CHARS = 2_000;

    /** The protocol's reason words (protocol.ts knowledge.result). */
    public static final String REASON_SIGNED_OUT = "signed-out";
    public static final String REASON_FORBIDDEN = "forbidden";
    public static final String REASON_UNAVAILABLE = "unavailable";

    /** Clamps topK into 1..MAX_TOP_K; absent or non-positive = the default. */
    public static int clampTopK(Object raw) {
        if (!(raw instanceof Number n)) {
            return DEFAULT_TOP_K;
        }
        long value = n.longValue();
        if (value < 1) {
            return DEFAULT_TOP_K;
        }
        return (int) Math.min(MAX_TOP_K, value);
    }

    /** Keeps only well-formed UUID strings, de-duplicated. The server binds
     * collectionIds to Set&lt;UUID&gt;, so one malformed id would 400 the whole
     * search; dropping it keeps the rest of the filter working. */
    public static List<String> collectionIds(Object raw) {
        List<String> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String id && isUuid(id.strip()) && !ids.contains(id.strip())) {
                    ids.add(id.strip());
                }
            }
        }
        return ids;
    }

    static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The JSON body for POST /api/knowledge/search. */
    public static Map<String, Object> requestBody(String query, int topK, List<String> collectionIds) {
        Map<String, Object> body = Json.object();
        body.put("query", query);
        body.put("topK", topK);
        if (collectionIds != null && !collectionIds.isEmpty()) {
            body.put("collectionIds", new ArrayList<>(collectionIds));
        }
        return body;
    }

    /** Validates and trims a query: null when there is nothing to search for.
     * Over-long queries are cut rather than refused. */
    public static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_QUERY_CHARS ? trimmed.substring(0, MAX_QUERY_CHARS) : trimmed;
    }

    /**
     * Maps one SearchResultResponse onto a KnowledgeHit map. Returns null for
     * an entry without an id or content - it cannot be shown or cited. The
     * nullable fields are written as explicit JSON null (Json.NULL), because
     * KnowledgeHit types them {@code string | null}, not optional.
     */
    public static Map<String, Object> toHit(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        String id = Json.str(raw, "id");
        String content = Json.str(raw, "content");
        if (id == null || id.isBlank() || content == null) {
            return null;
        }
        Map<String, Object> hit = Json.object();
        hit.put("id", id);
        hit.put("source", Json.orNull(Json.str(raw, "source")));
        hit.put("content", content);
        hit.put("collectionId", Json.orNull(Json.str(raw, "collectionId")));
        hit.put("documentId", Json.orNull(Json.str(raw, "documentId")));
        Object distance = raw.get("distance");
        hit.put("distance", distance instanceof Number n && Double.isFinite(n.doubleValue())
                ? (Object) Double.valueOf(n.doubleValue())
                : Json.NULL);
        return hit;
    }

    /** Maps the response array, skipping entries that are not objects or
     * that toHit rejects. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> toHits(List<Object> raw) {
        List<Map<String, Object>> hits = new ArrayList<>();
        if (raw == null) {
            return hits;
        }
        for (Object entry : raw) {
            if (entry instanceof Map<?, ?> map) {
                Map<String, Object> hit = toHit((Map<String, Object>) map);
                if (hit != null) {
                    hits.add(hit);
                }
            }
        }
        return hits;
    }

    /**
     * The knowledge.result reason for a failed search: 401 = signed-out,
     * 403 = forbidden, a connection failure (status 0), 404 or 5xx =
     * unavailable, anything else = the server's own message (short).
     */
    public static String reasonFor(int status, String message) {
        if (status == 401) {
            return REASON_SIGNED_OUT;
        }
        if (status == 403) {
            return REASON_FORBIDDEN;
        }
        if (status == 0 || status == 404 || status >= 500) {
            return REASON_UNAVAILABLE;
        }
        if (message == null || message.isBlank()) {
            return "Knowledge search failed (HTTP " + status + ").";
        }
        String line = message.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    /** The one-line knowledge.result map. */
    public static Map<String, Object> result(String reqId, List<Map<String, Object>> hits, String reason) {
        Map<String, Object> message = Json.object();
        message.put("type", "knowledge.result");
        message.put("reqId", reqId);
        message.put("ok", reason == null);
        if (reason == null) {
            message.put("hits", hits == null ? List.of() : hits);
        } else {
            message.put("reason", reason);
        }
        return message;
    }
}
