// The "VegaDuta" view: an SWT Browser hosting the shared chat webview, with a
// one-line status label underneath for things the page cannot report honestly
// (see LocalServerProbe).
//
// Private Mode is shown by the view itself, not only by the page: a "Private
// Mode" toggle in the view toolbar, and while it is on, the view's description
// line (above the browser) says what is blocked. Either the toggle or the
// page's own switch changes it; WebviewBridge keeps the two in step.
//
// The assembled page is written to the bundle's state location and loaded with
// setUrl() - WebviewPage's header explains why it is one inlined document and
// why it cannot be handed to setText().

package ai.vegaduta.ide.eclipse.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;
import org.osgi.framework.Bundle;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.auth.TokenStore;
import ai.vegaduta.ide.eclipse.privacy.PrivacyGate;
import ai.vegaduta.ide.eclipse.settings.VegadutaSettings;

public class VegadutaView extends ViewPart {

    public static final String VIEW_ID = "ai.vegaduta.ide.eclipse.view";

    /** The most recently created, not-yet-disposed view. The editor actions
     * stage their selection into this one. Single-window assumption, the same
     * one the JetBrains ChatSurfaceRegistry makes per project. */
    private static volatile VegadutaView active;

    private Browser browser;
    private Label status;
    private WebviewBridge bridge;
    private Runnable authListener;
    private Action privateModeAction;

    public static VegadutaView active() {
        VegadutaView view = active;
        return (view != null && view.bridge != null) ? view : null;
    }

    @Override
    public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        parent.setLayout(layout);

        browser = createBrowser(parent);
        if (browser == null) {
            Label failure = new Label(parent, SWT.WRAP);
            failure.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            failure.setText("VegaDuta could not create an embedded browser.\n\n"
                    + engineHint() + "\n\nDetails are in the Error Log view.");
            return;
        }
        browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        status = new Label(parent, SWT.NONE);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        status.setText(VegadutaSettings.localServerBaseUrl() == null
                ? "On-device engine: off - set a local inference server URL in Window > Preferences > VegaDuta"
                : "On-device engine: probing the local server...");

        bridge = new WebviewBridge(browser, this::setStatus);
        authListener = () -> {
            if (bridge != null) {
                bridge.onAuthChanged();
            }
        };
        TokenStore.get().addAuthListener(authListener);
        installPrivateModeToggle();

        loadPage();
        active = this;
        bridge.reportLocalServer();
    }

    /** The toolbar toggle plus the description line; both follow the bridge's
     * state, whichever side changed it. */
    private void installPrivateModeToggle() {
        privateModeAction = new Action("Private Mode", IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                if (bridge != null) {
                    bridge.setPrivateMode(isChecked());
                }
            }
        };
        privateModeAction.setToolTipText("Private Mode: keep prompts, code, attachments and search queries on"
                + " this machine. Blocks hosted chat, knowledge search, the code sandbox and workflow runs.");
        getViewSite().getActionBars().getToolBarManager().add(privateModeAction);
        getViewSite().getActionBars().updateActionBars();
        bridge.setPrivacyListener(this::showPrivateMode);
        showPrivateMode(bridge.isPrivateMode());
    }

    /** UI thread only (the bridge calls it through asyncExec). */
    private void showPrivateMode(boolean on) {
        if (bridge == null) {
            return; // the view was closed while the change was in flight
        }
        if (privateModeAction != null) {
            privateModeAction.setChecked(on);
        }
        setContentDescription(on ? PrivacyGate.VIEW_NOTICE : "");
    }

    /**
     * Creates the Browser, or returns null when this machine has no engine that
     * can run the chat bundle.
     *
     * On Windows the engine is asked for EXPLICITLY (SWT.EDGE) rather than left
     * to the DefaultType property, and a failure is reported instead of being
     * retried without the style bit. The reason is the failure mode: SWT's
     * Windows default is the Internet Explorer control, which cannot parse the
     * bundle at all (ES2022 loaded as a module), and it fails by rendering
     * nothing - a blank view with no error anywhere. A visible "install
     * WebView2" message is strictly better than that.
     *
     * Elsewhere SWT has exactly one engine (WebKitGTK on Linux, WKWebView on
     * macOS), so there is no choice to make and SWT.NONE is the honest style.
     *
     * SWTError, not Exception: SWT raises an Error when a widget's native
     * backing cannot be created.
     */
    private static Browser createBrowser(Composite parent) {
        boolean windows = org.eclipse.core.runtime.Platform.OS_WIN32
                .equals(org.eclipse.core.runtime.Platform.getOS());
        try {
            return new Browser(parent, windows ? SWT.EDGE : SWT.NONE);
        } catch (SWTError e) {
            VegadutaPlugin.logError("No usable SWT Browser engine is available", e);
            return null;
        }
    }

    private void loadPage() {
        String indexHtml = readBundleText("webview/index.html");
        if (indexHtml == null) {
            browser.setText(WebviewPage.MISSING_BUNDLE_HTML);
            setStatus("Chat UI not bundled - build clients/shared first (npm run build:webview).");
            return;
        }
        String head = WebviewPage.transportShim(WebviewBridge.HOST_FUNCTION)
                + WebviewPage.edgeSeedScript(
                        VegadutaSettings.apiBase(),
                        VegadutaSettings.localServerBaseUrl(),
                        VegadutaSettings.localServerModel());
        String page = WebviewPage.assemble(indexHtml, asset -> readBundleText("webview/" + asset), head);
        try {
            Path file = writePage(page);
            bridge.onPageReloading();
            browser.setUrl(file.toUri().toString());
        } catch (IOException e) {
            VegadutaPlugin.logError("Could not stage the chat page", e);
            browser.setText(WebviewPage.MISSING_BUNDLE_HTML);
            setStatus("Could not write the chat page to the plugin's state location - see the Error Log.");
        }
    }

    /**
     * Writes the assembled document into the bundle's state location.
     *
     * A fixed file name, overwritten each time: the page is several megabytes
     * (the shared bundle is inlined into it) and a per-open temp file would
     * leave copies behind on every restart.
     */
    private Path writePage(String page) throws IOException {
        Path dir = VegadutaPlugin.getDefault().getStateLocation().toFile().toPath().resolve("webview");
        Files.createDirectories(dir);
        Path file = dir.resolve("chat.html");
        Files.writeString(file, page, StandardCharsets.UTF_8);
        return file;
    }

    /** Reads a text file out of this bundle, or null when it is not there. */
    private static String readBundleText(String path) {
        Bundle bundle = (VegadutaPlugin.getDefault() == null) ? null : VegadutaPlugin.getDefault().getBundle();
        if (bundle == null) {
            return null;
        }
        URL url = FileLocator.find(bundle, new org.eclipse.core.runtime.Path(path), null);
        if (url == null) {
            return null;
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            VegadutaPlugin.logWarning("could not read bundled resource " + path, e);
            return null;
        }
    }

    /** Per-OS wording for "there is no browser engine here". States only what
     * SWT actually uses on each platform. */
    private static String engineHint() {
        String os = org.eclipse.core.runtime.Platform.getOS();
        if (org.eclipse.core.runtime.Platform.OS_WIN32.equals(os)) {
            return "On Windows this view needs the Microsoft Edge WebView2 runtime. Install the"
                    + " WebView2 Evergreen runtime and restart Eclipse.";
        }
        if (org.eclipse.core.runtime.Platform.OS_LINUX.equals(os)) {
            return "On Linux this view needs WebKitGTK (the webkit2gtk-4.1 or webkit2gtk-4.0 shared"
                    + " library). Install your distribution's WebKitGTK package and restart Eclipse.";
        }
        return "This platform's SWT Browser engine could not be created.";
    }

    private void setStatus(String text) {
        if (status == null || status.isDisposed()) {
            return;
        }
        status.getDisplay().asyncExec(() -> {
            if (!status.isDisposed()) {
                status.setText(text);
                status.getParent().layout();
            }
        });
    }

    /** Stages an editor selection into the chat composer. */
    public void sendSelection(String text, String languageId, String fileName) {
        if (bridge != null) {
            bridge.sendSelection(text, languageId, fileName);
        }
    }

    /** Stages a prompt plus context attachments in the composer (ui.prefill). */
    public void prefill(String text, java.util.List<String> contextKinds, boolean send) {
        if (bridge != null) {
            bridge.prefill(text, contextKinds, send);
        }
    }

    /** Re-reads settings and reloads the page. Called by the preference page,
     * because apiBase and the edge seed are baked into the document at load. */
    public void reloadFromSettings() {
        if (browser == null || browser.isDisposed() || bridge == null) {
            return;
        }
        loadPage();
        bridge.reportLocalServer();
    }

    /** Reloads every open VegaDuta view after a settings change. */
    public static void reloadAll() {
        VegadutaView view = active;
        if (view != null) {
            view.reloadFromSettings();
        }
    }

    @Override
    public void setFocus() {
        if (browser != null && !browser.isDisposed()) {
            browser.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (active == this) {
            active = null;
        }
        if (authListener != null) {
            TokenStore.get().removeAuthListener(authListener);
            authListener = null;
        }
        if (bridge != null) {
            bridge.dispose();
            bridge = null;
        }
        super.dispose();
    }
}
