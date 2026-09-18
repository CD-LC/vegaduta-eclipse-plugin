// Wire-shape decoding, the sandbox-language map, and the file-name ->
// languageId map. The point of most of these is the NEGATIVE case: an unknown
// value must be null or a documented default, never a guess.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ai.vegaduta.ide.eclipse.api.LocalServerProbe;
import ai.vegaduta.ide.eclipse.api.Models;
import ai.vegaduta.ide.eclipse.json.Json;
import ai.vegaduta.ide.eclipse.ui.LanguageIds;

class ModelsTest {

    @Test
    @DisplayName("the Keycloak realm and IDE client id match the realm export")
    void realmConstants() {
        // deploy/keycloak/agentic-ai-realm.json: clientId "agentic-ai-ide",
        // publicClient, oauth2.device.authorization.grant.enabled = "true".
        assertEquals("agentic-ai", Models.KEYCLOAK_REALM);
        assertEquals("agentic-ai-ide", Models.IDE_CLIENT_ID);
    }

    @Test
    @DisplayName("an agent decodes, and unknown server fields are ignored")
    void agentSummary() {
        Models.AgentSummary agent = Models.AgentSummary.from(Json.parseObject(
                "{\"id\":\"a1\",\"name\":\"Support\",\"model\":\"gpt\",\"somethingNew\":1}"));
        assertEquals("a1", agent.id());
        assertEquals("Support", agent.name());
        assertNull(agent.description());
        assertEquals("gpt", agent.model());
        // Round-trips back out to the webview without inventing nulls.
        assertEquals("{\"id\":\"a1\",\"name\":\"Support\",\"model\":\"gpt\"}", Json.write(agent.toMap()));
    }

    @Test
    @DisplayName("a workflow run decodes and knows its terminal states")
    void workflowRun() {
        Models.WorkflowRun run = Models.WorkflowRun.from(Json.parseObject(
                "{\"id\":\"r1\",\"workflowId\":\"w1\",\"status\":\"RUNNING\"}"));
        assertFalse(run.isTerminal());
        for (String status : new String[] { "COMPLETED", "FAILED", "CANCELLED" }) {
            assertTrue(Models.WorkflowRun.from(Json.parseObject(
                    "{\"id\":\"r\",\"workflowId\":\"w\",\"status\":\"" + status + "\"}")).isTerminal());
        }
    }

    @Test
    @DisplayName("a run with no status is treated as still running, not as done")
    void missingStatusIsNonTerminal() {
        Models.WorkflowRun run = Models.WorkflowRun.from(Json.parseObject("{\"id\":\"r\"}"));
        assertEquals("", run.status());
        assertFalse(run.isTerminal());
    }

    @Test
    @DisplayName("a sandbox result decodes with safe defaults")
    void runCodeResult() {
        Models.RunCodeResult result = Models.RunCodeResult.from(Json.parseObject("{\"stdout\":\"hi\"}"));
        assertEquals("hi", result.stdout());
        assertEquals("", result.stderr());
        assertEquals(-1, result.exitCode());
        assertFalse(result.timedOut());
    }

    @ParameterizedTest
    @DisplayName("editor languageIds map to the sandbox's three runtimes")
    @CsvSource({
        "python,python",
        "javascript,node",
        "typescript,node",
        "javascriptreact,node",
        "typescriptreact,node",
        "shellscript,bash",
        "bash,bash",
        "sh,bash",
        "PYTHON,python"
    })
    void sandboxLanguages(String languageId, String expected) {
        assertEquals(expected, Models.toSandboxLanguage(languageId));
    }

    @ParameterizedTest
    @DisplayName("anything else is null - the caller must NOT guess a runtime")
    @ValueSource(strings = { "java", "kotlin", "go", "ruby", "", "plaintext" })
    void unsupportedSandboxLanguages(String languageId) {
        assertNull(Models.toSandboxLanguage(languageId));
    }

    @Test
    @DisplayName("a null languageId is null, not a crash")
    void nullSandboxLanguage() {
        assertNull(Models.toSandboxLanguage(null));
    }

    // --- file name -> languageId --------------------------------------------

    @ParameterizedTest
    @DisplayName("known extensions map to the VS Code languageId vocabulary")
    @CsvSource({
        "Main.java,java",
        "script.py,python",
        "app.mjs,javascript",
        "Widget.tsx,typescriptreact",
        "build.gradle.kts,kotlin",
        "deploy.SH,shellscript",
        "notes.md,markdown"
    })
    void languageIds(String fileName, String expected) {
        assertEquals(expected, LanguageIds.forFileName(fileName));
    }

    @ParameterizedTest
    @DisplayName("an unknown or absent extension yields null rather than a guess")
    @ValueSource(strings = { "Makefile", "archive.tar.zst", "trailing.", "", "a.unknownext" })
    void unknownLanguageIds(String fileName) {
        assertNull(LanguageIds.forFileName(fileName));
    }

    @Test
    @DisplayName("a null file name is null")
    void nullLanguageId() {
        assertNull(LanguageIds.forFileName(null));
    }

    // --- local server probe --------------------------------------------------

    @Test
    @DisplayName("both /v1/models shapes in the wild count as a model list")
    void localServerModelListShapes() {
        assertTrue(LocalServerProbe.hasModel("{\"object\":\"list\",\"data\":[{\"id\":\"llama3\"}]}"));
        assertTrue(LocalServerProbe.hasModel("[{\"id\":\"llama3\"}]"));
        assertFalse(LocalServerProbe.hasModel("{\"data\":[]}"));
        assertFalse(LocalServerProbe.hasModel("[]"));
        assertFalse(LocalServerProbe.hasModel("{\"models\":[{\"id\":\"x\"}]}"));
        assertFalse(LocalServerProbe.hasModel("<html>"));
        assertFalse(LocalServerProbe.hasModel(""));
        assertFalse(LocalServerProbe.hasModel(null));
    }

    @Test
    @DisplayName("an unconfigured local server says so, and never claims a URL")
    void probeUnconfigured() {
        assertEquals(LocalServerProbe.Result.NOT_CONFIGURED, LocalServerProbe.probe(null));
        assertEquals(LocalServerProbe.Result.NOT_CONFIGURED, LocalServerProbe.probe("   "));
        String message = LocalServerProbe.describe(LocalServerProbe.Result.NOT_CONFIGURED, null);
        assertFalse(message.contains("null"), message);
        assertTrue(message.contains("Preferences"), message);
    }

    @Test
    @DisplayName("every probe outcome has its own sentence, and each names the address")
    void probeWording() {
        for (LocalServerProbe.Result result : LocalServerProbe.Result.values()) {
            String message = LocalServerProbe.describe(result, "http://127.0.0.1:11434");
            assertTrue(message.startsWith("On-device engine: "), message);
            if (result != LocalServerProbe.Result.NOT_CONFIGURED) {
                assertTrue(message.contains("http://127.0.0.1:11434"), message);
            }
        }
    }
}
