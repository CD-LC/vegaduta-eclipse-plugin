// Page assembly. Two of these are load-bearing and documented in
// WebviewPage's header:
//   - type="module" MUST survive inlining, or the esm bundle's trailing
//     `export` is a syntax error and the view silently stays blank;
//   - the transport shim and the edge seed must land BEFORE the app bundle,
//     because detectTransport() and createEngineHost() both read their inputs
//     at boot.

package ai.vegaduta.ide.eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.vegaduta.ide.eclipse.json.Json;
import ai.vegaduta.ide.eclipse.ui.WebviewPage;

class WebviewPageTest {

    /** The shape clients/shared/src/webview/chat/index.html actually has. */
    private static final String INDEX = "<!doctype html>\n<html lang=\"en\">\n<head>\n"
            + "  <meta charset=\"utf-8\" />\n"
            + "  <link rel=\"stylesheet\" href=\"chat.css\" />\n"
            + "</head>\n<body>\n  <main id=\"messages\"></main>\n"
            + "  <script type=\"module\" src=\"chat.js\"></script>\n</body>\n</html>";

    private static String asset(String name) {
        return switch (name) {
            case "chat.js" -> "console.log('app');\nexport { bootChatApp };";
            case "chat.css" -> "body { margin: 0 }";
            default -> null;
        };
    }

    @Test
    @DisplayName("inlines the bundle and keeps type=\"module\"")
    void inlinesKeepingModuleType() {
        String page = WebviewPage.assemble(INDEX, WebviewPageTest::asset, "<!--head-->");
        assertTrue(page.contains("<script type=\"module\">"), page);
        assertTrue(page.contains("export { bootChatApp };"), page);
        assertFalse(page.contains("src=\"chat.js\""), page);
    }

    @Test
    @DisplayName("inlines the stylesheet and leaves other <link> tags alone")
    void inlinesStylesheet() {
        String page = WebviewPage.assemble(
                INDEX.replace("<meta charset=\"utf-8\" />",
                        "<link rel=\"icon\" href=\"x.png\" />"),
                WebviewPageTest::asset, "");
        assertTrue(page.contains("<style>"), page);
        assertTrue(page.contains("body { margin: 0 }"), page);
        assertFalse(page.contains("href=\"chat.css\""), page);
        assertTrue(page.contains("href=\"x.png\""), page);
    }

    @Test
    @DisplayName("the head scripts land before the app bundle")
    void headScriptsComeFirst() {
        String page = WebviewPage.assemble(INDEX, WebviewPageTest::asset, "<!--MARKER-->");
        assertTrue(page.indexOf("<!--MARKER-->") < page.indexOf("console.log('app')"), page);
        assertTrue(page.indexOf("<head>") < page.indexOf("<!--MARKER-->"), page);
    }

    @Test
    @DisplayName("a document with no <head> still gets the shim, at the top")
    void noHeadElement() {
        String page = WebviewPage.assemble("<body><script src=\"chat.js\"></script></body>",
                WebviewPageTest::asset, "<!--MARKER-->");
        assertTrue(page.startsWith("<!--MARKER-->"), page);
    }

    @Test
    @DisplayName("a missing asset drops its tag instead of leaving a dead reference")
    void missingAssetDropsTag() {
        String page = WebviewPage.assemble(INDEX, name -> null, "");
        assertFalse(page.contains("chat.js"), page);
        assertFalse(page.contains("chat.css"), page);
    }

    @Test
    @DisplayName("relative and root-relative asset paths both resolve")
    void normalisesPaths() {
        assertEquals("chat.js", WebviewPage.normalise("./chat.js"));
        assertEquals("chat.js", WebviewPage.normalise("/chat.js"));
        assertEquals("chat.js", WebviewPage.normalise("chat.js"));
        assertEquals("a/b.js", WebviewPage.normalise("../a/b.js"));
    }

    @Test
    @DisplayName("a bundle containing $ or \\ is inlined literally (no regex expansion)")
    void inliningIsLiteral() {
        // Matcher.appendReplacement treats $ and \ as syntax; the bundle is
        // full of both. Getting this wrong corrupts the script silently.
        String page = WebviewPage.assemble(INDEX,
                name -> "chat.js".equals(name) ? "var x = \"$1 \\\\ $0\";" : null, "");
        assertTrue(page.contains("var x = \"$1 \\\\ $0\";"), page);
    }

    // --- the transport shim --------------------------------------------------

    @Test
    @DisplayName("the shim defines window.cefQuery over the named BrowserFunction")
    void shimShape() {
        String shim = WebviewPage.transportShim("__vegadutaToHost");
        // detectTransport() looks for exactly this global.
        assertTrue(shim.contains("window.cefQuery = function"), shim);
        assertTrue(shim.contains("window.__vegadutaToHost"), shim);
        // and it must queue, because the BrowserFunction may not exist yet.
        assertTrue(shim.contains("queue.push(request)"), shim);
    }

    // --- the edge seed -------------------------------------------------------

    @Test
    @DisplayName("the seed pins WebLLM off and carries the local server settings")
    void edgeSeedPinsWebllmOff() {
        String seed = WebviewPage.edgeSeedScript("https://api.vegaduta.ai", "http://127.0.0.1:11434", "llama3");
        assertTrue(seed.contains("\"edge.modelOverride\":\"hosted\""), seed);
        assertTrue(seed.contains("\"edge.backend\":\"ollama\""), seed);
        assertTrue(seed.contains("\"edge.apiBase\":\"https://api.vegaduta.ai\""), seed);
        assertTrue(seed.contains("\"edge.ollamaBaseUrl\":\"http://127.0.0.1:11434\""), seed);
        assertTrue(seed.contains("\"edge.ollamaModel\":\"llama3\""), seed);
    }

    @Test
    @DisplayName("unset local-server values are omitted, not seeded as empty strings")
    void edgeSeedOmitsUnset() {
        String seed = WebviewPage.edgeSeedScript("https://api.vegaduta.xyz", null, null);
        assertFalse(seed.contains("edge.ollamaBaseUrl"), seed);
        assertFalse(seed.contains("edge.ollamaModel"), seed);
        // The pin is unconditional even with no local server configured.
        assertTrue(seed.contains("\"edge.modelOverride\":\"hosted\""), seed);
    }

    // --- host -> webview delivery --------------------------------------------

    @Test
    @DisplayName("delivery double-encodes the payload into a JS string literal")
    void deliverScriptDoubleEncodes() {
        Map<String, Object> message = Json.object();
        message.put("type", "chat.chunk");
        message.put("delta", "a\nb \"q\" </script>");
        String script = WebviewPage.deliverScript(message);
        assertTrue(script.startsWith("window.__vegadutaDeliver && window.__vegadutaDeliver("), script);
        // No raw newline and no closing script tag may survive into the page.
        String argument = script.substring(script.indexOf('(') + 1, script.lastIndexOf(')'));
        assertFalse(argument.contains("\n"), argument);
        assertFalse(argument.contains("</script>"), argument);
        // ...and the argument still parses back to the exact payload JSON.
        Object inner = Json.parse(argument);
        assertEquals("a\nb \"q\" </script>", Json.str(Json.parseObject((String) inner), "delta"));
    }
}
