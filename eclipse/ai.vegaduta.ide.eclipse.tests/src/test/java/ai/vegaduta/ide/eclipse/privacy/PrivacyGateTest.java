// PrivacyGate: which webview messages Private Mode blocks, what each blocked
// request is answered with, persistence, and privacy.state.

package ai.vegaduta.ide.eclipse.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;

class PrivacyGateTest {

    private static Map<String, Object> msg(String json) {
        return Json.parseObject(json);
    }

    /** Every webview -> host message type protocol.ts defines that this host
     * handles, split by whether it carries user content to a hosted endpoint. */
    private static final List<String> HOSTED = List.of(
            "{\"type\":\"chat.send\",\"reqId\":\"r\",\"agentId\":\"a\",\"message\":\"secret code\"}",
            "{\"type\":\"knowledge.search\",\"reqId\":\"r\",\"query\":\"secret\"}",
            "{\"type\":\"sdlc.run\",\"reqId\":\"r\",\"code\":\"print(1)\",\"languageId\":\"python\"}",
            "{\"type\":\"workflow.run\",\"reqId\":\"r\",\"workflowId\":\"w\",\"input\":\"secret\"}");
    private static final List<String> LOCAL = List.of(
            "{\"type\":\"ready\"}",
            "{\"type\":\"auth.signIn\"}",
            "{\"type\":\"auth.signOut\"}",
            "{\"type\":\"chat.abort\",\"reqId\":\"r\"}",
            "{\"type\":\"context.request\",\"reqId\":\"r\",\"kinds\":[\"file\"]}",
            "{\"type\":\"ui.insert\",\"text\":\"x\"}",
            "{\"type\":\"ui.copy\",\"text\":\"x\"}",
            "{\"type\":\"ui.newFile\",\"text\":\"x\"}",
            "{\"type\":\"ui.reveal\",\"path\":\"a/b.java\",\"startLine\":1,\"endLine\":2}",
            "{\"type\":\"ui.openExternal\",\"url\":\"https://vegaduta.ai\"}",
            "{\"type\":\"engine.status\",\"status\":{}}",
            "{\"type\":\"privacy.mode\",\"private\":false}");

    @Test
    @DisplayName("off: nothing is blocked")
    void offAllowsEverything() {
        PrivacyGate gate = new PrivacyGate(false, null);
        for (String json : HOSTED) {
            assertFalse(gate.check(msg(json)).blocked(), json);
        }
    }

    @Test
    @DisplayName("on: exactly the four hosted paths are blocked; local ones pass")
    void onBlocksHostedOnly() {
        PrivacyGate gate = new PrivacyGate(true, null);
        for (String json : HOSTED) {
            assertTrue(gate.check(msg(json)).blocked(), json);
        }
        for (String json : LOCAL) {
            assertFalse(gate.check(msg(json)).blocked(), json);
        }
        assertEquals(List.of("chat.send", "knowledge.search", "sdlc.run", "workflow.run"),
                PrivacyGate.BLOCKED_TYPES);
    }

    @Test
    @DisplayName("each blocked request gets its own failure reply, carrying none of the user's content")
    void repliesPerType() {
        PrivacyGate gate = new PrivacyGate(true, null);
        Map<String, Object> chat = gate.check(msg(HOSTED.get(0))).reply();
        assertEquals("chat.error", chat.get("type"));
        assertEquals("r", chat.get("reqId"));
        assertTrue(((String) chat.get("message")).startsWith("Private Mode is on - hosted chat is blocked"));

        Map<String, Object> knowledge = gate.check(msg(HOSTED.get(1))).reply();
        assertEquals("knowledge.result", knowledge.get("type"));
        assertEquals(Boolean.FALSE, knowledge.get("ok"));
        assertTrue(((String) knowledge.get("reason")).contains("knowledge search is blocked"));
        assertNull(knowledge.get("hits"));

        Map<String, Object> sandbox = gate.check(msg(HOSTED.get(2))).reply();
        assertEquals("sdlc.run.result", sandbox.get("type"));
        assertEquals(Boolean.FALSE, sandbox.get("ok"));
        assertEquals("unavailable", sandbox.get("reason"));
        assertTrue(((String) sandbox.get("detail")).contains("code sandbox"));

        Map<String, Object> workflow = gate.check(msg(HOSTED.get(3))).reply();
        assertEquals("workflow.error", workflow.get("type"));
        assertEquals("r", workflow.get("reqId"));

        for (String json : HOSTED) {
            String written = Json.write(gate.check(msg(json)).reply());
            assertFalse(written.contains("secret"), written);
            assertFalse(written.contains("print(1)"), written);
        }
    }

    @Test
    @DisplayName("a blocked request without a reqId is still blocked, just not answered")
    void blockedWithoutReqId() {
        PrivacyGate.Decision d = new PrivacyGate(true, null).check(msg("{\"type\":\"chat.send\"}"));
        assertTrue(d.blocked());
        assertNull(d.reply());
    }

    @Test
    @DisplayName("set: only a real boolean changes state, and only a change is persisted")
    void persistence() {
        List<Boolean> saved = new ArrayList<>();
        PrivacyGate gate = new PrivacyGate(false, saved::add);
        assertFalse(gate.set(Boolean.FALSE), "no change");
        assertTrue(gate.set(Boolean.TRUE));
        assertTrue(gate.isPrivate());
        assertFalse(gate.set(Boolean.TRUE), "already on");
        assertFalse(gate.set("false"), "a string is not a boolean");
        assertFalse(gate.set(null));
        assertTrue(gate.isPrivate(), "malformed requests leave it on");
        assertTrue(gate.set(Boolean.FALSE));
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), saved);

        assertTrue(new PrivacyGate(true, null).isPrivate(), "the persisted value seeds the gate");
    }

    @Test
    @DisplayName("privacy.state reports the current value, enforced by this host")
    void stateMessage() {
        PrivacyGate gate = new PrivacyGate(false, null);
        assertEquals("{\"type\":\"privacy.state\",\"private\":false,\"enforced\":true}",
                Json.write(gate.stateMessage()));
        gate.set(Boolean.TRUE);
        assertEquals("{\"type\":\"privacy.state\",\"private\":true,\"enforced\":true}",
                Json.write(gate.stateMessage()));
    }

    @Test
    @DisplayName("the view notice states what is blocked and never claims there is no network")
    void viewNoticeWording() {
        String notice = PrivacyGate.VIEW_NOTICE;
        assertTrue(notice.contains("no prompt, code, attachment or search query leaves this machine"));
        assertTrue(notice.contains("Hosted chat, knowledge search, the code sandbox and workflow runs are blocked"));
        assertFalse(notice.toLowerCase(java.util.Locale.ROOT).contains("nothing touches the network"));
        assertFalse(notice.toLowerCase(java.util.Locale.ROOT).contains("offline"));
    }
}
