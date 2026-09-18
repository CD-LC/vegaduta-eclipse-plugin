// Wire shapes for the platform endpoints this plugin calls. Field names match
// the Java DTOs on the server and mirror clients/shared/src/api/types.ts and
// clients/jetbrains/.../api/Models.kt exactly - only the fields the plugin
// consumes are declared; the server may send more.
//
// Each record carries a from(Map) factory rather than a reflective binder, so
// an unknown or missing field is a documented default, never a parse failure.

package ai.vegaduta.ide.eclipse.api;

import java.util.Map;
import java.util.Set;

import ai.vegaduta.ide.eclipse.json.Json;

public final class Models {

    private Models() {
    }

    /** Keycloak realm and the public client the IDE plugins authenticate as.
     * Both read out of deploy/keycloak/agentic-ai-realm.json - the client is
     * "agentic-ai-ide", publicClient, with
     * oauth2.device.authorization.grant.enabled = true. Do not invent others. */
    public static final String KEYCLOAK_REALM = "agentic-ai";
    public static final String IDE_CLIENT_ID = "agentic-ai-ide";

    /** Terminal run states checked by the workflow poller; anything else is
     * treated as still running. */
    public static final Set<String> TERMINAL_RUN_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");

    /** Subset of core's AgentResponse. */
    public record AgentSummary(String id, String name, String description, String model) {
        public static AgentSummary from(Map<String, Object> o) {
            return new AgentSummary(
                    Json.str(o, "id"),
                    Json.str(o, "name"),
                    Json.str(o, "description"),
                    Json.str(o, "model"));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = Json.object();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("model", model);
            return m;
        }
    }

    /** Subset of core's WorkflowResponse. */
    public record WorkflowSummary(String id, String name, String description) {
        public static WorkflowSummary from(Map<String, Object> o) {
            return new WorkflowSummary(
                    Json.str(o, "id"),
                    Json.str(o, "name"),
                    Json.str(o, "description"));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = Json.object();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            return m;
        }
    }

    /** Matches core's WorkflowRunResponse (same shape on the JWT and dev/v1
     * surfaces). A run whose status is missing is reported as "" and therefore
     * treated as non-terminal by the poller, which is the safe direction. */
    public record WorkflowRun(
            String id,
            String workflowId,
            String workflowName,
            String status,
            String requestedBy,
            String errorMessage,
            String startedAt,
            String completedAt,
            String outputFileName) {

        public static WorkflowRun from(Map<String, Object> o) {
            return new WorkflowRun(
                    Json.str(o, "id"),
                    Json.str(o, "workflowId"),
                    Json.str(o, "workflowName"),
                    Json.str(o, "status", ""),
                    Json.str(o, "requestedBy"),
                    Json.str(o, "errorMessage"),
                    Json.str(o, "startedAt"),
                    Json.str(o, "completedAt"),
                    Json.str(o, "outputFileName"));
        }

        public boolean isTerminal() {
            return TERMINAL_RUN_STATUSES.contains(status);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = Json.object();
            m.put("id", id);
            m.put("workflowId", workflowId);
            m.put("workflowName", workflowName);
            m.put("status", status);
            m.put("requestedBy", requestedBy);
            m.put("errorMessage", errorMessage);
            m.put("startedAt", startedAt);
            m.put("completedAt", completedAt);
            m.put("outputFileName", outputFileName);
            return m;
        }
    }

    /** POST /api/sdlc/sandbox/run-code's result. */
    public record RunCodeResult(String stdout, String stderr, int exitCode, boolean timedOut) {
        public static RunCodeResult from(Map<String, Object> o) {
            return new RunCodeResult(
                    Json.str(o, "stdout", ""),
                    Json.str(o, "stderr", ""),
                    (int) Json.num(o, "exitCode", -1),
                    Json.bool(o, "timedOut", false));
        }
    }

    /** Mirror of clients/shared/src/api/sdlc.ts's toSandboxLanguage() and the
     * JetBrains twin: maps a common editor languageId to the sandbox's three
     * runtimes. Returns null for anything else - callers must NOT guess a
     * fallback; the protocol's typed "unavailable" reason exists for this. */
    public static String toSandboxLanguage(String languageId) {
        if (languageId == null) {
            return null;
        }
        return switch (languageId.toLowerCase(java.util.Locale.ROOT)) {
            case "python" -> "python";
            case "javascript", "typescript", "javascriptreact", "typescriptreact" -> "node";
            case "shellscript", "bash", "sh" -> "bash";
            default -> null;
        };
    }
}
