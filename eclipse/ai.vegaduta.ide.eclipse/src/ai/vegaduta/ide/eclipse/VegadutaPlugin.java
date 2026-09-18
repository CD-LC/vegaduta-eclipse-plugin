// Bundle activator and the plugin's logging front door.
//
// The one thing it does beyond the usual boilerplate is set
// org.eclipse.swt.browser.DefaultType=edge on Windows - see the comment on
// chooseWindowsBrowserEngine() and README.md's "Per-OS browser engine"
// section. SWT reads that property when a Browser is constructed WITHOUT an
// explicit engine style bit; VegadutaView does not rely on it alone and asks
// for SWT.EDGE directly, so the property is belt-and-braces for any other
// Browser this plugin might grow.

package ai.vegaduta.ide.eclipse;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class VegadutaPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "ai.vegaduta.ide.eclipse";

    /** SWT's browser-engine selector, consulted when a Browser is constructed
     * with no explicit engine style bit. Set during bundle start so it is in
     * place before anything in this plugin builds a Browser. */
    public static final String SWT_BROWSER_TYPE_PROPERTY = "org.eclipse.swt.browser.DefaultType";

    private static volatile VegadutaPlugin plugin;

    /** The bundled on-device model runtime (runtime/LocalRuntime). Created
     * with the bundle, shut down with it - which ends llama-server and its
     * children, so no model server outlives Eclipse. */
    private volatile ai.vegaduta.ide.eclipse.runtime.LocalRuntime localRuntime;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        chooseWindowsBrowserEngine();
        localRuntime = new ai.vegaduta.ide.eclipse.runtime.LocalRuntime();
        // Background Job only: reconciles settings and, if the person's model
        // is installed and they did not stop it, starts it.
        localRuntime.startUp();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        ai.vegaduta.ide.eclipse.runtime.LocalRuntime runtime = localRuntime;
        localRuntime = null;
        if (runtime != null) {
            try {
                runtime.shutdown();
            } catch (RuntimeException e) {
                logWarning("stopping the on-device model server failed", e);
            }
        }
        plugin = null;
        super.stop(context);
    }

    public static VegadutaPlugin getDefault() {
        return plugin;
    }

    /** The bundled runtime, or null when the bundle is not active. */
    public static ai.vegaduta.ide.eclipse.runtime.LocalRuntime localRuntime() {
        VegadutaPlugin current = plugin;
        return current == null ? null : current.localRuntime;
    }

    /**
     * On Windows, ask SWT for the Edge (WebView2 / Chromium) Browser rather
     * than the default Internet Explorer control. The IE control cannot run the
     * shared chat bundle at all: it is ES2022 output loaded as an ES module.
     *
     * Deliberately does NOT override a value the user or the launcher already
     * set - somebody who pinned a browser type had a reason.
     *
     * On Linux (WebKitGTK) and macOS (WKWebView) SWT has exactly one engine and
     * this property is not consulted; nothing is set there.
     */
    static void chooseWindowsBrowserEngine() {
        if (!Platform.OS_WIN32.equals(Platform.getOS())) {
            return;
        }
        String existing = System.getProperty(SWT_BROWSER_TYPE_PROPERTY);
        if (existing != null && !existing.isBlank()) {
            logInfo("SWT browser type already pinned to '" + existing + "' - leaving it alone");
            return;
        }
        System.setProperty(SWT_BROWSER_TYPE_PROPERTY, "edge");
    }

    // --- logging -------------------------------------------------------------

    public static void logInfo(String message) {
        log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    public static void logWarning(String message, Throwable error) {
        log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
    }

    public static void logError(String message, Throwable error) {
        log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }

    /** Never throws, and works before/after the bundle is active: logging must
     * not be the thing that breaks a failure path. */
    private static void log(IStatus status) {
        VegadutaPlugin current = plugin;
        if (current != null) {
            current.getLog().log(status);
        } else {
            System.err.println("[" + PLUGIN_ID + "] " + status.getMessage());
        }
    }
}
