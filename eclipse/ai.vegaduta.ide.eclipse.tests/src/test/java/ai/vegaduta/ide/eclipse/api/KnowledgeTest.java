// Knowledge: request body, SearchResultResponse -> KnowledgeHit mapping, and
// the knowledge.result reasons - everything about knowledge.search that does
// not need a live server.

package ai.vegaduta.ide.eclipse.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class KnowledgeTest {

    private static final String ID = "3f2b8c1e-5d4a-4b6f-9e2d-1a2b3c4d5e6f";
    private static final String COLLECTION = "0e1d2c3b-4a59-4687-b6a5-948372615041";
    private static final String DOCUMENT = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    @Test
    @DisplayName("a full SearchResultResponse maps to KnowledgeHit, dropping chunkIndex")
    void mapsFullHit() {
        String body = "[{\"id\":\"" + ID + "\",\"source\":\"runbook.md\",\"chunkIndex\":3,\"content\":\"Restart the"
                + " worker.\",\"collectionId\":\"" + COLLECTION + "\",\"distance\":0.125,\"documentId\":\"" + DOCUMENT
                + "\"}]";
        List<Map<String, Object>> hits = Knowledge.toHits(Json.parseArray(body));
        assertEquals(1, hits.size());
        assertEquals("{\"id\":\"" + ID + "\",\"source\":\"runbook.md\",\"content\":\"Restart the worker.\","
                + "\"collectionId\":\"" + COLLECTION + "\",\"documentId\":\"" + DOCUMENT + "\",\"distance\":0.125}",
                Json.write(hits.get(0)));
    }

    @Test
    @DisplayName("nullable fields are written as explicit JSON null, not dropped")
    void nullableFieldsAreNull() {
        String body = "[{\"id\":\"" + ID + "\",\"source\":null,\"content\":\"text\",\"collectionId\":null,"
                + "\"distance\":-0.5}]";
        List<Map<String, Object>> hits = Knowledge.toHits(Json.parseArray(body));
        assertEquals("{\"id\":\"" + ID + "\",\"source\":null,\"content\":\"text\",\"collectionId\":null,"
                + "\"documentId\":null,\"distance\":-0.5}", Json.write(hits.get(0)));

        Map<String, Object> noDistance = Knowledge.toHit(Json.parseObject("{\"id\":\"x\",\"content\":\"c\"}"));
        assertEquals(Json.NULL, noDistance.get("distance"));
    }

    @Test
    @DisplayName("entries without an id or content, and non-objects, are skipped")
    void skipsUnusable() {
        String body = "[{\"content\":\"no id\"},{\"id\":\"" + ID + "\"},42,null,\"x\",{\"id\":\"ok\",\"content\":\"\"}]";
        List<Map<String, Object>> hits = Knowledge.toHits(Json.parseArray(body));
        assertEquals(1, hits.size(), "an empty-string content is still a hit");
        assertEquals("ok", hits.get(0).get("id"));
        assertEquals(List.of(), Knowledge.toHits(null));
        assertNull(Knowledge.toHit(null));
    }

    @Test
    @DisplayName("request body: query, clamped topK, only well-formed collection UUIDs")
    void requestBody() {
        List<String> ids = Knowledge.collectionIds(Arrays.asList(COLLECTION, "not-a-uuid", 7, null, COLLECTION,
                " " + ID + " ", "3f2b8c1e5d4a4b6f9e2d1a2b3c4d5e6f"));
        assertEquals(List.of(COLLECTION, ID), ids);
        assertEquals("{\"query\":\"how do we deploy\",\"topK\":5,\"collectionIds\":[\"" + COLLECTION + "\",\""
                + ID + "\"]}", Json.write(Knowledge.requestBody("how do we deploy", 5, ids)));
        assertEquals("{\"query\":\"q\",\"topK\":3}", Json.write(Knowledge.requestBody("q", 3, List.of())));

        assertEquals(5, Knowledge.clampTopK(null));
        assertEquals(5, Knowledge.clampTopK(0L));
        assertEquals(5, Knowledge.clampTopK("7"));
        assertEquals(7, Knowledge.clampTopK(7L));
        assertEquals(20, Knowledge.clampTopK(500L));
        assertEquals(2, Knowledge.clampTopK(2.9));
    }

    @Test
    @DisplayName("queries are trimmed, blank ones refused, long ones cut")
    void normalizeQuery() {
        assertEquals("deploy", Knowledge.normalizeQuery("  deploy \n"));
        assertNull(Knowledge.normalizeQuery("   "));
        assertNull(Knowledge.normalizeQuery(null));
        assertEquals(Knowledge.MAX_QUERY_CHARS, Knowledge.normalizeQuery("q".repeat(5000)).length());
    }

    @Test
    @DisplayName("reasons: 401 signed-out, 403 forbidden, transport/404/5xx unavailable, else the message")
    void reasons() {
        assertEquals("signed-out", Knowledge.reasonFor(401, "whatever"));
        assertEquals("forbidden", Knowledge.reasonFor(403, "nope"));
        assertEquals("unavailable", Knowledge.reasonFor(0, "Could not reach"));
        assertEquals("unavailable", Knowledge.reasonFor(404, "not found"));
        assertEquals("unavailable", Knowledge.reasonFor(503, "down"));
        assertEquals("query must not be blank", Knowledge.reasonFor(400, "query must not be blank\nstack"));
        assertEquals("Knowledge search failed (HTTP 422).", Knowledge.reasonFor(422, null));
        assertEquals(200, Knowledge.reasonFor(400, "x".repeat(900)).length());
    }

    @Test
    @DisplayName("knowledge.result: hits on success, reason (and no hits) on failure")
    void resultMessage() {
        assertEquals("{\"type\":\"knowledge.result\",\"reqId\":\"r1\",\"ok\":true,\"hits\":[]}",
                Json.write(Knowledge.result("r1", List.of(), null)));
        assertEquals("{\"type\":\"knowledge.result\",\"reqId\":\"r1\",\"ok\":false,\"reason\":\"signed-out\"}",
                Json.write(Knowledge.result("r1", null, "signed-out")));
    }
}
