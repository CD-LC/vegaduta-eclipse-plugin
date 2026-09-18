// Assembles the single HTML document the SWT Browser loads, out of the shared
// webview bundle (clients/shared/dist/webview, copied into the bundle at
// webview/ by the maven-resources-plugin execution in the plugin's pom.xml).
//
// Everything here is pure string work with an injected asset loader, so the
// assembly rules below are unit-tested (WebviewPageTest) without an SWT
// Display.
//
// WHY ONE INLINED DOCUMENT, AND WHY IT GOES TO A FILE
//
//  - `<script type="module" src="chat.js">` CANNOT be used from a file:// URL:
//    Chromium (Edge WebView2 on Windows) and WebKit both apply CORS to module
//    script fetches, and a file:// origin fails that check. So the script has
//    to be INLINED - an inline module script is not fetched and not subject to
//    CORS.
//  - The module type must be PRESERVED when inlining. build-webview.mjs emits
//    chat.js with esbuild format:"esm", so the file ends in a top-level
//    `export { bootChatApp }`, which is only legal in module context. Dropping
//    type="module" makes the engine fail to parse the script, bootChatApp()
//    never runs, and the view stays blank - silently. This is the same trap
//    JcefBridge.kt documents.
//  - The assembled document is written to a FILE and loaded with setUrl(),
//    not handed to Browser.setText(). On Windows SWT's Edge implementation
//    routes setText() through WebView2's NavigateToString, which refuses
//    strings over ~2 MB; the bundle's chat.js alone is several MB. Loading a
//    file URL has no such limit.
//
// Token rule: nothing assembled here ever carries an access token. The seeded
// values are a public API base and local-inference preferences.

package ai.vegaduta.ide.eclipse.ui;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.vegaduta.ide.eclipse.json.Json;

public final class WebviewPage {

    /** Matches `<script ... src="..."></script>`, capturing the attributes on
     * either side of src so type="module" can be detected. */
    private static final Pattern SCRIPT_SRC =
            Pattern.compile("<script([^>]*)\\bsrc=[\"']([^\"']+)[\"']([^>]*)>\\s*</script>");

    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>");
    private static final Pattern HREF = Pattern.compile("\\bhref=[\"']([^\"']+)[\"']");
    private static final Pattern MODULE_TYPE = Pattern.compile("\\btype=[\"']module[\"']");

    private WebviewPage() {
    }

    /** Shown instead of the chat app when the shared bundle was never built. */
    public static final String MISSING_BUNDLE_HTML =
            "<html><body style=\"font:13px sans-serif;padding:16px\">"
                    + "<h3>VegaDuta chat UI not bundled</h3>"
                    + "<p>This build of the plugin carries no webview bundle. Build it with"
                    + " <code>cd clients/shared &amp;&amp; npm install &amp;&amp; npm run build:webview</code>"
                    + " and rebuild the plugin.</p></body></html>";

    /**
     * Builds the complete document.
     *
     * @param indexHtml the bundle's index.html.
     * @param assets    resolves a bundle-relative asset path (e.g. "chat.js")
     *                  to its text, or null when it is missing. A missing
     *                  asset drops its tag rather than leaving a broken
     *                  reference behind.
     * @param headScripts markup injected at the very top of &lt;head&gt;, before
     *                  the app bundle can evaluate - the transport shim and the
     *                  edge seed.
     */
    public static String assemble(String indexHtml, Function<String, String> assets, String headScripts) {
        String out = inlineScripts(indexHtml, assets);
        out = inlineStylesheets(out, assets);
        int head = out.indexOf("<head>");
        if (head >= 0) {
            return out.substring(0, head + "<head>".length()) + headScripts + out.substring(head + "<head>".length());
        }
        return headScripts + out;
    }

    private static String inlineScripts(String html, Function<String, String> assets) {
        Matcher matcher = SCRIPT_SRC.matcher(html);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String attributes = matcher.group(1) + matcher.group(3);
            String source = assets.apply(normalise(matcher.group(2)));
            String replacement;
            if (source == null) {
                replacement = "";
            } else {
                String open = MODULE_TYPE.matcher(attributes).find()
                        ? "<script type=\"module\">"
                        : "<script>";
                replacement = open + "\n" + source + "\n</script>";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String inlineStylesheets(String html, Function<String, String> assets) {
        Matcher matcher = LINK_TAG.matcher(html);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String tag = matcher.group();
            String replacement = tag;
            if (tag.contains("stylesheet")) {
                Matcher href = HREF.matcher(tag);
                if (href.find()) {
                    String source = assets.apply(normalise(href.group(1)));
                    replacement = (source == null) ? "" : "<style>\n" + source + "\n</style>";
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** "./chat.js" and "/chat.js" both name the bundle-relative "chat.js". */
    public static String normalise(String path) {
        int i = 0;
        while (i < path.length() && (path.charAt(i) == '.' || path.charAt(i) == '/')) {
            i++;
        }
        return path.substring(i);
    }

    // --- the injected head ---------------------------------------------------

    /**
     * The webview->host transport shim. The shared chat app calls
     * detectTransport() (clients/shared/src/webview/bridge.ts) AT BOOT and
     * looks for window.cefQuery, so this must be defined before the app bundle
     * evaluates. Implementing cefQuery over the SWT BrowserFunction named
     * {@code __vegadutaToHost} means the Eclipse host reuses the JetBrains
     * branch of detectTransport() verbatim - no fork of the shared protocol or
     * of the shared transport detection.
     *
     * The queue is not decoration: SWT injects a BrowserFunction into each
     * document as it loads, and on some engines that can land after an inline
     * script has already run. Anything posted before the function exists is
     * queued and drained, rather than dropped.
     */
    public static String transportShim(String functionName) {
        return "<script>(function () {\n"
                + "  var queue = [];\n"
                + "  var timer = null;\n"
                + "  function host() { return window." + functionName + "; }\n"
                + "  function drain() {\n"
                + "    if (typeof host() !== 'function') { return; }\n"
                + "    if (timer) { clearInterval(timer); timer = null; }\n"
                + "    var pending = queue.splice(0, queue.length);\n"
                + "    for (var i = 0; i < pending.length; i++) {\n"
                + "      try { host()(pending[i]); } catch (postError) {}\n"
                + "    }\n"
                + "  }\n"
                + "  window.cefQuery = function (args) {\n"
                + "    var request = args && args.request;\n"
                + "    if (typeof request !== 'string') { return; }\n"
                + "    if (typeof host() === 'function') {\n"
                + "      try { host()(request); } catch (postError) {}\n"
                + "      return;\n"
                + "    }\n"
                + "    queue.push(request);\n"
                + "    if (!timer) { timer = setInterval(drain, 25); }\n"
                + "  };\n"
                + "})();</script>";
    }

    /**
     * Seeds the shared edge layer's preference store (EdgeKv, localStorage-
     * backed - clients/shared/src/edge/host.ts) BEFORE the app bundle runs.
     * A direct port of JcefBridge.edgeSeedScript(), for the same two reasons:
     *
     *  - carry plugin settings into the page, which has no other way to read
     *    them: chat/main.ts calls createEngineHost() with no config, so every
     *    value comes from kv - edge.apiBase, edge.ollamaBaseUrl,
     *    edge.ollamaModel;
     *  - keep WebLLM OFF deterministically. edge.modelOverride="hosted" makes
     *    webllmEngine.probe() return false before it detects capabilities or
     *    offers a downloadable model, and edge.backend="ollama" puts the
     *    local-HTTP backend first in the probe order. Relying on WebGPU
     *    detection merely failing is not good enough: on Windows the Edge
     *    engine is Chromium and MIGHT expose WebGPU, and a half-started
     *    multi-gigabyte model download inside an IDE view is not a thing to
     *    leave to chance. No WebGPU path in this plugin has been verified to
     *    work on any OS, so none is offered.
     *
     * The page is loaded from a file URL, where localStorage can throw; the
     * shared createDefaultKv() would then fall back to an empty in-memory map
     * and lose these values, so a memory-backed stand-in is installed under the
     * same name first. Every step is try/catch-ed: a page that cannot store
     * preferences must still boot.
     */
    public static String edgeSeedScript(String apiBase, String localServerBaseUrl, String localServerModel) {
        Map<String, Object> seed = Json.object();
        seed.put("edge.apiBase", apiBase);
        seed.put("edge.backend", "ollama");
        seed.put("edge.modelOverride", "hosted");
        seed.put("edge.ollamaBaseUrl", localServerBaseUrl); // null is dropped by Json.write
        seed.put("edge.ollamaModel", localServerModel);
        return "<script>(function () {\n"
                + "  var seed = " + Json.write(seed) + ";\n"
                + "  var store = null;\n"
                + "  try {\n"
                + "    window.localStorage.setItem('vegaduta.probe', '1');\n"
                + "    window.localStorage.removeItem('vegaduta.probe');\n"
                + "    store = window.localStorage;\n"
                + "  } catch (storageError) { store = null; }\n"
                + "  if (!store) {\n"
                + "    var mem = {};\n"
                + "    store = {\n"
                + "      getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; },\n"
                + "      setItem: function (k, v) { mem[k] = String(v); },\n"
                + "      removeItem: function (k) { delete mem[k]; },\n"
                + "      clear: function () { mem = {}; },\n"
                + "      key: function (i) { return Object.keys(mem)[i] || null; }\n"
                + "    };\n"
                + "    try { Object.defineProperty(store, 'length', { get: function () { return Object.keys(mem).length; } }); } catch (lengthError) {}\n"
                + "    try { Object.defineProperty(window, 'localStorage', { value: store, configurable: true }); } catch (defineError) {}\n"
                + "  }\n"
                + "  try {\n"
                + "    for (var k in seed) {\n"
                + "      if (Object.prototype.hasOwnProperty.call(seed, k)) { store.setItem(k, seed[k]); }\n"
                + "    }\n"
                + "  } catch (seedError) {}\n"
                + "})();</script>";
    }

    /**
     * The host->webview delivery call. The payload JSON is written as a JS
     * STRING LITERAL argument (double encoding), exactly as JcefBridge does,
     * because window.__vegadutaDeliver takes a string and parses it itself.
     */
    public static String deliverScript(Map<String, Object> message) {
        String literal = Json.write(Json.write(message));
        return "window.__vegadutaDeliver && window.__vegadutaDeliver(" + literal + ");";
    }
}
