// Private Mode, host side. The one place this plugin decides whether a
// webview message may reach a hosted endpoint.
//
// While Private Mode is on, NO prompt, code, attachment or search query leaves
// the machine through this host:
//   BLOCKED  chat.send        (hosted chat)
//            knowledge.search (hosted knowledge base)
//            sdlc.run         (the code sandbox runs in the platform's cloud)
//            workflow.run     (workflows run on the platform)
// Eclipse has no other hosted path that carries user content: it sends no
// hosted completions, no hosted editor commands and no code validation (those
// exist in other hosts). Every editor command here only STAGES a prompt in the
// composer, and staging goes nowhere.
//   ALLOWED  the user's own local model server and on-device inference (in
//            this host only the local server's status probe, which sends no
//            content - Eclipse has no on-device chat), and the host's own
//            calls that carry no user content: sign-in, and listing
//            agents/workflows.
// So Private Mode is NOT "no network at all", and nothing here claims it is.
//
// A blocked message is answered with that request's own failure message
// (chat.error, knowledge.result, sdlc.run.result, workflow.error) so the chat
// UI never waits on a reply that will not come.
//
// Pure (no SWT, no OSGi): the persisted value comes in through the
// constructor and goes out through the `persist` callback, so the gate is
// unit-tested directly (PrivacyGateTest).

package ai.vegaduta.ide.eclipse.privacy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import ai.vegaduta.ide.eclipse.json.Json;

public final class PrivacyGate {

    /** Webview -> host message types that reach a hosted endpoint with the
     * user's content. */
    public static final List<String> BLOCKED_TYPES = List.of("chat.send", "knowledge.search", "sdlc.run",
            "workflow.run");

    /** The short line the view shows while Private Mode is on. */
    public static final String VIEW_NOTICE = "Private Mode: no prompt, code, attachment or search query leaves"
            + " this machine. Hosted chat, knowledge search, the code sandbox and workflow runs are blocked.";

    /** Eclipse has no on-device chat (README "Your own local model server"),
     * so the honest advice is to turn Private Mode off - not to "use your
     * local model", which this host cannot chat with. */
    static final String CHAT_BLOCKED = "Private Mode is on - hosted chat is blocked, so this message was not"
            + " sent. Chat in Eclipse runs on the hosted service; turn Private Mode off to send it.";
    static final String KNOWLEDGE_BLOCKED = "Private Mode is on - knowledge search is blocked, so the query"
            + " was not sent.";
    static final String SANDBOX_BLOCKED = "Private Mode is on - the code sandbox runs on the platform, so the"
            + " code was not sent to it.";
    static final String WORKFLOW_BLOCKED = "Private Mode is on - workflows run on the platform, so nothing was"
            + " sent.";

    /** What the gate decided about one message. {@code reply} is the failure
     * to post back, or null when there is no reqId to answer. */
    public record Decision(boolean blocked, Map<String, Object> reply) {
        static final Decision ALLOW = new Decision(false, null);
    }

    private final AtomicBoolean on;
    private final Consumer<Boolean> persist;

    /**
     * @param initial the persisted value
     * @param persist called with the new value whenever it CHANGES; may be null
     */
    public PrivacyGate(boolean initial, Consumer<Boolean> persist) {
        this.on = new AtomicBoolean(initial);
        this.persist = persist;
    }

    public boolean isPrivate() {
        return on.get();
    }

    /**
     * Sets Private Mode. Only a real boolean changes anything - a malformed
     * privacy.mode leaves the current state as it is. Returns true when the
     * value changed (and was persisted).
     */
    public boolean set(Object requested) {
        if (!(requested instanceof Boolean value)) {
            return false;
        }
        boolean changed = on.getAndSet(value) != value;
        if (changed && persist != null) {
            persist.accept(value);
        }
        return changed;
    }

    /** privacy.state as this host reports it: enforced is always true,
     * because this host blocks its own hosted paths (see the header). */
    public Map<String, Object> stateMessage() {
        Map<String, Object> message = Json.object();
        message.put("type", "privacy.state");
        message.put("private", on.get());
        message.put("enforced", Boolean.TRUE);
        return message;
    }

    public static boolean isHostedType(String type) {
        return type != null && BLOCKED_TYPES.contains(type);
    }

    /** Decides one webview message. Anything not in BLOCKED_TYPES, and every
     * message while Private Mode is off, is allowed. */
    public Decision check(Map<String, Object> message) {
        String type = Json.str(message, "type");
        if (!on.get() || !isHostedType(type)) {
            return Decision.ALLOW;
        }
        String reqId = Json.str(message, "reqId");
        if (reqId == null) {
            return new Decision(true, null);
        }
        Map<String, Object> reply = Json.object();
        switch (type) {
            case "chat.send" -> {
                reply.put("type", "chat.error");
                reply.put("reqId", reqId);
                reply.put("message", CHAT_BLOCKED);
            }
            case "knowledge.search" -> {
                reply.put("type", "knowledge.result");
                reply.put("reqId", reqId);
                reply.put("ok", Boolean.FALSE);
                reply.put("reason", KNOWLEDGE_BLOCKED);
            }
            case "sdlc.run" -> {
                reply.put("type", "sdlc.run.result");
                reply.put("reqId", reqId);
                reply.put("ok", Boolean.FALSE);
                reply.put("reason", "unavailable");
                reply.put("detail", SANDBOX_BLOCKED);
            }
            default -> { // workflow.run
                reply.put("type", "workflow.error");
                reply.put("reqId", reqId);
                reply.put("message", WORKFLOW_BLOCKED);
            }
        }
        return new Decision(true, reply);
    }
}
