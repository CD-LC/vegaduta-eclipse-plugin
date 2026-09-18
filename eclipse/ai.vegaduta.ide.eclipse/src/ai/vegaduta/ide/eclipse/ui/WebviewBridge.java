// Host side of clients/shared/src/webview/protocol.ts over an SWT Browser.
// The message switch below mirrors JcefBridge.kt's one for one; where it
// differs, the difference is commented.
//
// Transport:
//  - webview -> host: an SWT BrowserFunction named __vegadutaToHost, wrapped
//    by an in-page window.cefQuery shim (WebviewPage.transportShim). Using the
//    cefQuery SHAPE means the shared detectTransport() needs no new branch and
//    the shared protocol is not forked.
//  - host -> webview: browser.execute() calling window.__vegadutaDeliver(json).
//    Posts are queued until the page sends "ready".
//
// Threading: SWT is single-threaded. Every browser call goes through the
// Display; every network call goes to a background executor. Nothing here may
// block the UI thread.
//
// Token rule: the access token NEVER enters the webview. SSE runs host-side in
// ApiClient and arrives as chat.chunk messages, exactly as on VS Code and
// JetBrains.
//
// Private Mode: every webview message passes PrivacyGate.check() before the
// switch, so the hosted paths (chat.send, knowledge.search, sdlc.run,
// workflow.run) are refused in ONE place while it is on - see PrivacyGate.
//
// Bundled on-device runtime: runtime.query / runtime.install / runtime.stop
// go to runtime/LocalRuntime; every state change comes back as runtime.status
// through post(), and once the server is running (or gone) init is re-sent so
// the page sees the new local-server address - see buildInit().

package ai.vegaduta.ide.eclipse.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Display;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.api.ApiClient;
import ai.vegaduta.ide.eclipse.api.ApiException;
import ai.vegaduta.ide.eclipse.api.Knowledge;
import ai.vegaduta.ide.eclipse.api.LocalServerProbe;
import ai.vegaduta.ide.eclipse.api.Models;
import ai.vegaduta.ide.eclipse.auth.DeviceFlowLogin;
import ai.vegaduta.ide.eclipse.auth.TokenStore;
import ai.vegaduta.ide.eclipse.context.ContextAssembler;
import ai.vegaduta.ide.eclipse.context.ContextItems;
import ai.vegaduta.ide.eclipse.context.GitDiff;
import ai.vegaduta.ide.eclipse.context.GitLog;
import ai.vegaduta.ide.eclipse.json.Json;
import ai.vegaduta.ide.eclipse.privacy.PrivacyGate;
import ai.vegaduta.ide.eclipse.runtime.LocalRuntime;
import ai.vegaduta.ide.eclipse.runtime.RuntimeCatalog;
import ai.vegaduta.ide.eclipse.runtime.RuntimeStatus;
import ai.vegaduta.ide.eclipse.runtime.RuntimeWiring;
import ai.vegaduta.ide.eclipse.settings.VegadutaSettings;

public final class WebviewBridge {

    /** The BrowserFunction the in-page cefQuery shim calls. */
    public static final String HOST_FUNCTION = "__vegadutaToHost";

    /** How long a workflow is polled before the view gives up and says so.
     * Matches the JetBrains bridge. */
    private static final long WORKFLOW_DEADLINE_MS = 10 * 60_000;
    private static final long WORKFLOW_POLL_MS = 2000;

    private final Browser browser;
    /** Captured at construction: Widget.getDisplay() throws once the widget is
     * disposed, and deliver() runs on background threads that can outlive the
     * view (workflow polling in particular). */
    private final Display display;
    private final Consumer<String> statusReporter;
    private final ExecutorService work = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "vegaduta-bridge");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, AtomicBoolean> activeChats = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> pendingPosts = new ArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    private boolean pageReady; // guarded by pendingPosts
    /** Bumped on every page (re)load, so an init built for the previous page
     * never marks the NEW page ready before it has said so. Guarded by
     * pendingPosts. */
    private int pageGeneration;

    private BrowserFunction hostFunction;

    /** Private Mode, seeded from the preference store and persisted back to
     * it on every change. */
    private final PrivacyGate privacy = new PrivacyGate(VegadutaSettings.privateMode(),
            VegadutaSettings::setPrivateMode);
    /** Told the new value whenever Private Mode changes, from either side (the
     * page's toggle or the view's toolbar button). Called on the UI thread. */
    private volatile Consumer<Boolean> privacyListener;

    /** Forwards the bundled runtime's state to the page. Registered for the
     * bridge's lifetime; the runtime calls it from its job threads, and post()
     * hops to the UI thread. */
    private final LocalRuntime.Listener runtimeListener = new LocalRuntime.Listener() {
        @Override
        public void statusChanged(RuntimeStatus status) {
            LocalRuntime runtime = VegadutaPlugin.localRuntime();
            if (runtime != null) {
                post(status.toMessage(RuntimeCatalog.MODELS, runtime::isModelInstalled));
            }
        }

        @Override
        public void settingsChanged() {
            resendInit();
            reportLocalServer();
        }
    };

    public WebviewBridge(Browser browser, Consumer<String> statusReporter) {
        this.browser = browser;
        this.display = browser.getDisplay();
        this.statusReporter = statusReporter;
        LocalRuntime runtime = VegadutaPlugin.localRuntime();
        if (runtime != null) {
            runtime.addListener(runtimeListener);
        }
        this.hostFunction = new BrowserFunction(browser, HOST_FUNCTION) {
            @Override
            public Object function(Object[] arguments) {
                if (arguments != null && arguments.length > 0 && arguments[0] instanceof String raw) {
                    try {
                        onWebviewMessage(raw);
                    } catch (RuntimeException e) {
                        VegadutaPlugin.logWarning("webview message failed", e);
                    }
                }
                return null;
            }
        };
    }

    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        LocalRuntime runtime = VegadutaPlugin.localRuntime();
        if (runtime != null) {
            // Closing the view does NOT stop the server: it keeps serving
            // until Stop or Eclipse exits (bundle stop ends it).
            runtime.removeListener(runtimeListener);
        }
        for (AtomicBoolean flag : activeChats.values()) {
            flag.set(true);
        }
        activeChats.clear();
        if (hostFunction != null && !hostFunction.isDisposed()) {
            hostFunction.dispose();
        }
        hostFunction = null;
        work.shutdownNow();
    }

    /**
     * Runs network work off the UI thread.
     *
     * Every background task in this class goes through here so that a message
     * arriving DURING teardown - the page can still fire engine.status while
     * the part is closing - is dropped quietly instead of surfacing as a
     * RejectedExecutionException from the shut-down executor.
     */
    private void submit(Runnable task) {
        if (disposed.get()) {
            return;
        }
        try {
            work.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Lost the race with dispose(). Nothing to report: the view is gone.
        }
    }

    /** Called when the Browser starts loading a new document: the page's
     * "ready" has not arrived yet, so posts must queue again. Without this a
     * reload would deliver into a page that has no __vegadutaDeliver. */
    public void onPageReloading() {
        synchronized (pendingPosts) {
            pageReady = false;
            pageGeneration++;
            pendingPosts.clear();
        }
    }

    // --- Private Mode --------------------------------------------------------

    public boolean isPrivateMode() {
        return privacy.isPrivate();
    }

    public void setPrivacyListener(Consumer<Boolean> listener) {
        this.privacyListener = listener;
    }

    /**
     * Turns Private Mode on or off from EITHER side - privacy.mode from the
     * page, or the view's toolbar toggle - persists it, and always tells the
     * page the result (privacy.state), so the two can never disagree.
     */
    public void setPrivateMode(boolean on) {
        privacy.set(on);
        post(privacy.stateMessage());
        Consumer<Boolean> listener = privacyListener;
        if (listener != null) {
            boolean now = privacy.isPrivate();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() -> listener.accept(now));
            }
        }
    }

    // --- ChatSurface ---------------------------------------------------------

    /** Stages an editor selection into the chat composer. Same message the
     * VS Code and JetBrains hosts send. */
    public void sendSelection(String text, String languageId, String fileName) {
        Map<String, Object> message = Json.object();
        message.put("type", "selection.context");
        message.put("text", text);
        message.put("languageId", languageId);
        message.put("fileName", fileName);
        post(message);
    }

    /**
     * ui.prefill: stages {@code text} in the composer and asks the chat app to
     * request and attach {@code contextKinds}. {@code send} is passed through
     * as given; the editor commands pass false, so the person always sees the
     * prompt and its attachments before anything is spent.
     */
    public void prefill(String text, List<String> contextKinds, boolean send) {
        Map<String, Object> message = Json.object();
        message.put("type", "ui.prefill");
        message.put("text", text);
        if (contextKinds != null && !contextKinds.isEmpty()) {
            message.put("context", new ArrayList<>(contextKinds));
        }
        message.put("send", send);
        post(message);
    }

    // --- webview -> host -----------------------------------------------------

    private void onWebviewMessage(String raw) {
        Map<String, Object> message;
        try {
            message = Json.parseObject(raw);
        } catch (RuntimeException e) {
            VegadutaPlugin.logWarning("unparseable webview message", e);
            return;
        }
        String type = Json.str(message, "type");
        if (type == null) {
            return;
        }
        PrivacyGate.Decision decision = privacy.check(message);
        if (decision.blocked()) {
            // Refused BEFORE any network work is scheduled; the request's own
            // failure message tells the chat UI, so nothing waits forever.
            if (decision.reply() != null) {
                post(decision.reply());
            }
            return;
        }
        switch (type) {
            case "ready" -> sendInitThenFlush();
            case "auth.signIn" -> DeviceFlowLogin.signIn(reason -> {
                if (reason != null) {
                    report("Sign-in failed: " + reason);
                }
                // Success needs no report here: TokenStore fires its auth
                // listener, which the view turns into auth.changed.
            });
            case "auth.signOut" -> TokenStore.get().signOut(); // listener posts auth.changed
            case "chat.send" -> handleChatSend(message);
            case "chat.abort" -> {
                AtomicBoolean flag = activeChats.get(Json.str(message, "reqId"));
                if (flag != null) {
                    flag.set(true);
                }
            }
            case "workflow.run" -> handleWorkflowRun(message);
            case "engine.status" -> handleEngineStatus(message);
            case "ui.insert" -> EditorBridge.insertAtCaret(Json.str(message, "text"), this::report);
            case "ui.copy" -> EditorBridge.copyToClipboard(Json.str(message, "text"));
            case "ui.openExternal" -> {
                String url = Json.str(message, "url");
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    DeviceFlowLogin.openInBrowser(url);
                }
            }
            case "sdlc.run" -> handleSdlcRun(message);
            case "context.request" -> handleContextRequest(message);
            case "ui.reveal" -> EditorBridge.reveal(Json.str(message, "path"),
                    Json.num(message, "startLine", 0), Json.num(message, "endLine", 0), this::report);
            case "knowledge.search" -> handleKnowledgeSearch(message);
            case "runtime.query" -> postRuntimeStatus();
            case "runtime.install" -> {
                LocalRuntime runtime = VegadutaPlugin.localRuntime();
                String modelId = Json.str(message, "modelId");
                if (runtime == null) {
                    post(runtimeUnavailable(modelId));
                } else if (modelId != null) {
                    // Returns at once; the work runs in an Eclipse Job and
                    // reports through runtimeListener.
                    runtime.install(modelId);
                }
            }
            case "runtime.stop" -> {
                LocalRuntime runtime = VegadutaPlugin.localRuntime();
                if (runtime != null) {
                    runtime.stop();
                }
            }
            case "privacy.mode" -> {
                Object requested = message.get("private");
                if (requested instanceof Boolean on) {
                    setPrivateMode(on);
                } else {
                    post(privacy.stateMessage()); // malformed: report, change nothing
                }
            }
            case "ui.newFile" -> {
                String text = Json.str(message, "text");
                String languageId = Json.str(message, "languageId");
                if (text != null) {
                    // The scratch file is written off the UI thread; only the
                    // editor open hops back onto it (EditorBridge).
                    submit(() -> EditorBridge.openInNewEditor(text, languageId, this::report));
                }
            }
            case "ui.setCommitMessage" -> {
                // init.capabilities.commitMessage is false, so the chat app
                // should never send this. If a page does anyway, the text is
                // not dropped: it goes to the clipboard and the status line
                // says so. Eclipse's commit box belongs to EGit, which is
                // optional, and this plugin takes no dependency on it.
                String text = Json.str(message, "text");
                if (text != null && !text.isEmpty()) {
                    EditorBridge.copyToClipboard(text);
                    report("Commit message copied to the clipboard - paste it into the Git Staging view.");
                }
            }
            default -> {
                // engine.result only ever answers a host-sent engine.request,
                // and this host sends none. download.* is handled inside the
                // shared chat app against its own engine host, which the edge
                // seed pins to "hosted" so no download can start here at all.
            }
        }
    }

    // --- init / auth ---------------------------------------------------------

    /**
     * Answers "ready": builds init (which may fetch the agent lists over the
     * network, hence the background thread), delivers it, and only THEN marks
     * the page ready and drains the queue - so a ui.prefill or
     * selection.context staged by an editor command before the view existed
     * lands after init, never before it.
     *
     * Delivering init and draining happen under the pendingPosts lock.
     * deliver() only schedules an asyncExec, so holding the lock is cheap, and
     * it guarantees a concurrent post() cannot overtake the drained backlog.
     */
    private void sendInitThenFlush() {
        int generation;
        synchronized (pendingPosts) {
            generation = pageGeneration;
        }
        submit(() -> {
            Map<String, Object> init;
            try {
                init = buildInit();
            } catch (RuntimeException e) {
                // Still flip to ready below: a failed init must not strand
                // every queued message behind it.
                VegadutaPlugin.logError("building the chat init message failed", e);
                init = null;
            }
            synchronized (pendingPosts) {
                if (generation != pageGeneration) {
                    return; // the page reloaded meanwhile; its own ready will come
                }
                if (init != null) {
                    deliver(init);
                }
                // The persisted Private Mode, right after init: a page that
                // has just loaded must not assume "off" while this host is
                // enforcing "on".
                deliver(privacy.stateMessage());
                pageReady = true;
                for (Map<String, Object> queued : pendingPosts) {
                    deliver(queued);
                }
                pendingPosts.clear();
            }
        });
    }

    private Map<String, Object> buildInit() {
        TokenStore tokens = TokenStore.get();
        boolean signedIn = tokens.isSignedIn() || tokens.apiKey() != null;
        Lists lists = fetchListsSafely(signedIn);
        Map<String, Object> message = Json.object();
        message.put("type", "init");
        // "eclipse" is a member of the shared EnginePlatform union since
        // 2026-09-18 (clients/shared/src/webview/protocol.ts). Before that
        // this host reported "jetbrains", the nearest member by behaviour,
        // because the union simply lacked it.
        message.put("platform", "eclipse");
        String theme = ideTheme();
        if (theme != null) {
            message.put("theme", theme);
        }
        message.put("apiBase", VegadutaSettings.apiBase());
        message.put("auth", authJson());
        message.put("agents", lists.agentMaps());
        message.put("workflows", lists.workflowMaps());
        // hostLocalEngine turns on the page's engine host, which in this host
        // can only ever mean its LOCAL-HTTP backend (edge/ollamaEngine.ts):
        //  - WebGPU/WebLLM stays off by construction. The edge seed pins
        //    edge.modelOverride="hosted" so webllmEngine.probe() bails, and
        //    no WebGPU path has been verified in ANY SWT Browser engine.
        //  - The local-HTTP backend fetches the server FROM THE PAGE, and this
        //    page is a file:// document, so the request carries a "null" (or,
        //    on WebKit, "file://") Origin and is subject to CORS.
        // So it is TRUE only while the configured local server IS the bundled
        // llama-server this plugin started (RuntimeWiring.hostLocalEngine).
        // That server's CORS behaviour is known from its source: llama.cpp
        // b11037 defaults to --cors-origins "*" with credentials on, which
        // makes it reflect whatever Origin arrives, "null" included, and
        // answer preflights for any header. A server the person runs
        // themselves (Ollama rejects an unknown Origin by default) stays
        // FALSE: claiming an engine and then failing invisibly is worse than
        // not claiming it. For that case the host-side probe (see
        // reportLocalServer) is the readout - /v1/models from Java, where no
        // CORS applies.
        // Not yet observed in a running Eclipse on any engine: see README
        // "What is not verified".
        String localBase = VegadutaSettings.localServerBaseUrl();
        LocalRuntime runtime = VegadutaPlugin.localRuntime();
        message.put("hostLocalEngine",
                RuntimeWiring.hostLocalEngine(localBase, runtime == null ? null : runtime.status()));
        // The page's engine reads its server address from kv, seeded when the
        // page loads (WebviewPage.edgeSeedScript). Sending it again here lets
        // a server started AFTER the load be used without reloading the page.
        message.put("edgeSettings", RuntimeWiring.edgeSettings(localBase, VegadutaSettings.localServerModel()));
        // knowledge: false only when the sole credential is a vmcp_ API key,
        // whose surface has no knowledge routes (see ContextItems).
        message.put("capabilities",
                ContextItems.hostCapabilities(tokens.isSignedIn() || tokens.apiKey() == null,
                        runtime != null && runtime.isSupported()));
        return message;
    }

    /**
     * Re-sends init to a page that is already up, so it sees a changed
     * local-server address and hostLocalEngine (protocol.ts runtime.install:
     * "once running, re-sends init"). A page still booting gets a fresh init
     * from its own "ready", so nothing is sent to it here.
     */
    private void resendInit() {
        int generation;
        synchronized (pendingPosts) {
            if (!pageReady) {
                return;
            }
            generation = pageGeneration;
        }
        submit(() -> {
            Map<String, Object> init;
            try {
                init = buildInit();
            } catch (RuntimeException e) {
                VegadutaPlugin.logError("rebuilding the chat init message failed", e);
                return;
            }
            synchronized (pendingPosts) {
                if (generation != pageGeneration || !pageReady) {
                    return;
                }
                deliver(init);
                deliver(privacy.stateMessage());
            }
        });
    }

    // --- bundled runtime -----------------------------------------------------

    /** runtime.query -> runtime.status. Reads marker files, so off the UI
     * thread. */
    private void postRuntimeStatus() {
        submit(() -> {
            LocalRuntime runtime = VegadutaPlugin.localRuntime();
            post(runtime == null ? runtimeUnavailable(null) : runtime.statusMessage());
        });
    }

    private static Map<String, Object> runtimeUnavailable(String modelId) {
        return new RuntimeStatus(RuntimeStatus.State.ERROR, modelId, null,
                "The on-device runtime is not available - the VegaDuta plugin is not active.", null)
                .toMessage(RuntimeCatalog.MODELS, model -> false);
    }

    /**
     * The workbench theme ("dark" / "light"), because the SWT Browser only
     * knows the OS preference and a dark Eclipse on a light OS is common.
     * Read from the e4 theme engine's preference; null (follow the OS) when
     * it cannot be read.
     */
    static String ideTheme() {
        try {
            String id = org.eclipse.core.runtime.Platform.getPreferencesService()
                    .getString("org.eclipse.e4.ui.css.swt.theme", "themeid", "", null);
            return themeFromId(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static String themeFromId(String themeId) {
        if (themeId == null || themeId.isEmpty()) {
            return null;
        }
        return themeId.toLowerCase(java.util.Locale.ROOT).contains("dark") ? "dark" : "light";
    }

    // --- context -------------------------------------------------------------

    /**
     * context.request -> context.result. The editor is read NOW, on the UI
     * thread this BrowserFunction callback runs on (EditorContext.snapshot
     * syncExecs if it ever is not). git and the truncation run on the
     * background executor, so a slow repository never freezes the IDE.
     */
    private void handleContextRequest(Map<String, Object> message) {
        String reqId = Json.str(message, "reqId");
        if (reqId == null) {
            return;
        }
        List<String> unsupported = new ArrayList<>();
        List<String> kinds = ContextItems.knownKinds(message.get("kinds"), unsupported);
        ContextAssembler.EditorSnapshot snapshot = EditorContext.snapshot();
        submit(() -> {
            Map<String, Object> result = Json.object();
            result.put("type", "context.result");
            result.put("reqId", reqId);
            try {
                ContextAssembler.Assembled assembled = ContextAssembler.assemble(kinds, unsupported, snapshot,
                        GitDiff::run, GitLog::run);
                result.put("items", assembled.items());
                if (!assembled.missing().isEmpty()) {
                    result.put("missing", assembled.missing());
                }
            } catch (RuntimeException e) {
                // Every requested kind is answered, even on an unexpected
                // failure: the chat UI waits on this reqId.
                VegadutaPlugin.logWarning("assembling chat context failed", e);
                List<Map<String, Object>> missing = new ArrayList<>();
                for (String kind : kinds) {
                    missing.add(ContextItems.missing(kind, "Could not read this context - see the Error Log."));
                }
                result.put("items", List.of());
                result.put("missing", missing);
            }
            post(result);
        });
    }

    /** Re-sends auth and the agent/workflow lists after a sign-in or sign-out. */
    public void onAuthChanged() {
        submit(() -> {
            Map<String, Object> changed = Json.object();
            changed.put("type", "auth.changed");
            changed.put("auth", authJson());
            post(changed);

            TokenStore tokens = TokenStore.get();
            Lists lists = fetchListsSafely(tokens.isSignedIn() || tokens.apiKey() != null);
            Map<String, Object> agents = Json.object();
            agents.put("type", "agents.changed");
            agents.put("agents", lists.agentMaps());
            agents.put("workflows", lists.workflowMaps());
            post(agents);
        });
    }

    private Map<String, Object> authJson() {
        TokenStore tokens = TokenStore.get();
        Map<String, Object> auth = Json.object();
        auth.put("signedIn", tokens.isSignedIn() || tokens.apiKey() != null);
        auth.put("username", tokens.username());
        auth.put("mode", tokens.authMode());
        return auth;
    }

    private record Lists(List<Models.AgentSummary> agents, List<Models.WorkflowSummary> workflows) {
        List<Map<String, Object>> agentMaps() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Models.AgentSummary agent : agents) {
                out.add(agent.toMap());
            }
            return out;
        }

        List<Map<String, Object>> workflowMaps() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Models.WorkflowSummary workflow : workflows) {
                out.add(workflow.toMap());
            }
            return out;
        }
    }

    /** A failed listing is an empty list plus a log line, never an exception:
     * the chat UI must still come up when one endpoint is unhappy. */
    private Lists fetchListsSafely(boolean signedIn) {
        if (!signedIn) {
            return new Lists(List.of(), List.of());
        }
        List<Models.AgentSummary> agents = List.of();
        List<Models.WorkflowSummary> workflows = List.of();
        try {
            agents = ApiClient.get().listAgents();
        } catch (RuntimeException e) {
            VegadutaPlugin.logInfo("listAgents failed: " + e.getMessage());
        }
        try {
            workflows = ApiClient.get().listWorkflows();
        } catch (RuntimeException e) {
            VegadutaPlugin.logInfo("listWorkflows failed: " + e.getMessage());
        }
        return new Lists(agents, workflows);
    }

    // --- chat ----------------------------------------------------------------

    private void handleChatSend(Map<String, Object> message) {
        String reqId = Json.str(message, "reqId");
        String agentId = Json.str(message, "agentId");
        String text = Json.str(message, "message");
        if (reqId == null || agentId == null || text == null) {
            return;
        }
        String sessionId = Json.str(message, "sessionId");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeChats.put(reqId, cancelled);
        submit(() -> {
            try {
                ApiClient.ChatResult result = ApiClient.get().streamChat(agentId, text, sessionId, cancelled,
                        delta -> {
                            Map<String, Object> chunk = Json.object();
                            chunk.put("type", "chat.chunk");
                            chunk.put("reqId", reqId);
                            chunk.put("delta", delta);
                            post(chunk);
                        });
                Map<String, Object> done = Json.object();
                done.put("type", "chat.done");
                done.put("reqId", reqId);
                done.put("sessionId", result.sessionId());
                post(done);
            } catch (RuntimeException e) {
                Map<String, Object> error = Json.object();
                error.put("type", "chat.error");
                error.put("reqId", reqId);
                error.put("message", e.getMessage() == null ? "Chat failed." : e.getMessage());
                post(error);
            } finally {
                activeChats.remove(reqId);
            }
        });
    }

    // --- knowledge -----------------------------------------------------------

    /** knowledge.search -> knowledge.result. Always answers the reqId. */
    private void handleKnowledgeSearch(Map<String, Object> message) {
        String reqId = Json.str(message, "reqId");
        if (reqId == null) {
            return;
        }
        String query = Knowledge.normalizeQuery(Json.str(message, "query"));
        if (query == null) {
            post(Knowledge.result(reqId, null, "Type something to search the knowledge base for."));
            return;
        }
        if (!TokenStore.get().isSignedIn()) {
            // Signed out, or an API key only: the vmcp_ surface has no
            // knowledge routes, so do not send a request that can only fail.
            post(Knowledge.result(reqId, null, Knowledge.REASON_SIGNED_OUT));
            return;
        }
        int topK = Knowledge.clampTopK(message.get("topK"));
        List<String> collections = Knowledge.collectionIds(message.get("collectionIds"));
        submit(() -> {
            try {
                post(Knowledge.result(reqId, ApiClient.get().searchKnowledge(query, topK, collections), null));
            } catch (ApiException e) {
                post(Knowledge.result(reqId, null, Knowledge.reasonFor(e.status(), e.getMessage())));
            } catch (RuntimeException e) {
                VegadutaPlugin.logWarning("knowledge search failed", e);
                post(Knowledge.result(reqId, null, Knowledge.REASON_UNAVAILABLE));
            }
        });
    }

    // --- workflows -----------------------------------------------------------

    private void handleWorkflowRun(Map<String, Object> message) {
        String reqId = Json.str(message, "reqId");
        String workflowId = Json.str(message, "workflowId");
        if (reqId == null || workflowId == null) {
            return;
        }
        String input = Json.str(message, "input", "");
        submit(() -> {
            try {
                Models.WorkflowRun run = ApiClient.get().runWorkflow(workflowId, input);
                postWorkflowStatus(reqId, run);
                long deadline = System.currentTimeMillis() + WORKFLOW_DEADLINE_MS;
                while (!run.isTerminal() && System.currentTimeMillis() < deadline && !disposed.get()) {
                    Thread.sleep(WORKFLOW_POLL_MS);
                    run = ApiClient.get().getWorkflowRun(workflowId, run.id());
                    postWorkflowStatus(reqId, run);
                }
                if (disposed.get()) {
                    return;
                }
                if (!run.isTerminal()) {
                    Map<String, Object> error = Json.object();
                    error.put("type", "workflow.error");
                    error.put("reqId", reqId);
                    error.put("message",
                            "Still running after 10 minutes - check the web console for the result.");
                    post(error);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Map<String, Object> error = Json.object();
                error.put("type", "workflow.error");
                error.put("reqId", reqId);
                error.put("message", e.getMessage() == null ? "Workflow run failed." : e.getMessage());
                post(error);
            }
        });
    }

    private void postWorkflowStatus(String reqId, Models.WorkflowRun run) {
        Map<String, Object> status = Json.object();
        status.put("type", "workflow.status");
        status.put("reqId", reqId);
        status.put("run", run.toMap());
        post(status);
    }

    // --- sandbox -------------------------------------------------------------

    private void handleSdlcRun(Map<String, Object> message) {
        String reqId = Json.str(message, "reqId");
        if (reqId == null) {
            return;
        }
        String code = Json.str(message, "code", "");
        String languageId = Json.str(message, "languageId");
        String language = Models.toSandboxLanguage(languageId);
        if (language == null) {
            Map<String, Object> result = Json.object();
            result.put("type", "sdlc.run.result");
            result.put("reqId", reqId);
            result.put("ok", false);
            result.put("reason", "unavailable");
            result.put("detail", languageId != null
                    ? "\"" + languageId + "\" isn't runnable here - the sandbox supports python, node"
                            + " (JS/TS), and bash."
                    : "Tag the code block with a language, e.g. ```python, so the sandbox knows what to run.");
            post(result);
            return;
        }
        submit(() -> {
            try {
                Models.RunCodeResult run = ApiClient.get().runCode(code, language);
                Map<String, Object> result = Json.object();
                result.put("type", "sdlc.run.result");
                result.put("reqId", reqId);
                result.put("ok", true);
                result.put("stdout", run.stdout());
                result.put("stderr", run.stderr());
                result.put("exitCode", run.exitCode());
                result.put("timedOut", run.timedOut());
                post(result);
            } catch (ApiException e) {
                post(sdlcFailure(reqId, e.status() == 403 ? "forbidden" : "unavailable",
                        e.getMessage() == null ? "Run failed." : e.getMessage()));
            } catch (RuntimeException e) {
                post(sdlcFailure(reqId, "unknown", e.getMessage() == null ? "Run failed." : e.getMessage()));
            }
        });
    }

    private static Map<String, Object> sdlcFailure(String reqId, String reason, String detail) {
        Map<String, Object> result = Json.object();
        result.put("type", "sdlc.run.result");
        result.put("reqId", reqId);
        result.put("ok", false);
        result.put("reason", reason);
        result.put("detail", detail);
        return result;
    }

    // --- engine status -------------------------------------------------------

    /**
     * engine.status from the webview updates the label under the browser.
     *
     * The shared `detail` is LOGGED, never shown. It is written for a host
     * where several backends compete and, with WebLLM pinned off by the edge
     * seed, it describes that pin rather than the user's local server. This
     * host answers the question with its own probe instead - see
     * LocalServerProbe's header for why the page cannot answer it here at all.
     */
    private void handleEngineStatus(Map<String, Object> message) {
        Map<String, Object> status = Json.obj(message, "status");
        if (status == null) {
            return;
        }
        VegadutaPlugin.logInfo("webview engine status: " + Json.str(status, "state", "unavailable")
                + " (" + Json.str(status, "detail", "no detail") + ")");
        reportLocalServer();
    }

    /** Probes the user's local inference server on a background thread and puts
     * the verdict in the status line. Safe to call repeatedly. */
    public void reportLocalServer() {
        String baseUrl = VegadutaSettings.localServerBaseUrl();
        submit(() -> report(LocalServerProbe.describe(LocalServerProbe.probe(baseUrl), baseUrl)));
    }

    private void report(String text) {
        if (statusReporter != null) {
            statusReporter.accept(text);
        }
    }

    // --- host -> webview -----------------------------------------------------

    /**
     * The check-then-add here is atomic with the "ready" handler's
     * flip-then-drain (both synchronized on pendingPosts). Without that, a post
     * can read pageReady=false, the ready handler can flip and drain an empty
     * queue, and only THEN does this post's add() run - stranding the message
     * until some unrelated later post happens to drain the backlog. The
     * JetBrains bridge carries the same fix and the same comment.
     */
    private void post(Map<String, Object> message) {
        boolean deliverNow;
        synchronized (pendingPosts) {
            if (pageReady) {
                deliverNow = true;
            } else {
                pendingPosts.add(message);
                deliverNow = false;
            }
        }
        if (deliverNow) {
            deliver(message);
        }
    }

    private void deliver(Map<String, Object> message) {
        if (disposed.get()) {
            return;
        }
        String script = WebviewPage.deliverScript(message);
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            // Re-checked ON the UI thread: long-lived background work (workflow
            // polling in particular) can still be in flight when the view is
            // closed, and browser.execute() on a disposed widget throws.
            if (disposed.get() || browser.isDisposed()) {
                return;
            }
            try {
                browser.execute(script);
            } catch (RuntimeException e) {
                VegadutaPlugin.logWarning("delivering to the webview failed", e);
            }
        });
    }
}
