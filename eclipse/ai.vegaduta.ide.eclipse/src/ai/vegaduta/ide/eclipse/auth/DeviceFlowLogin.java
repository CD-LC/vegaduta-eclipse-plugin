// The transport half of the OAuth device flow. Every decision it makes is
// delegated to DeviceFlow (pure, unit-tested); this class only does HTTP, a
// progress Job, opening the user's browser, and storing the result.

package ai.vegaduta.ide.eclipse.auth;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.PlatformUI;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.api.ApiException;
import ai.vegaduta.ide.eclipse.api.Models;
import ai.vegaduta.ide.eclipse.settings.Environments;
import ai.vegaduta.ide.eclipse.settings.VegadutaSettings;

public final class DeviceFlowLogin {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private DeviceFlowLogin() {
    }

    /**
     * Runs the whole flow in a cancellable background Job.
     *
     * @param onResult called with null on success, or a human-readable reason
     *        on failure. Never called with an exception: the plugin's layers
     *        return typed outcomes rather than throwing at their callers.
     */
    public static void signIn(Consumer<String> onResult) {
        Job job = new Job("Signing in to VegaDuta") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    doSignIn(monitor);
                    if (onResult != null) {
                        onResult.accept(null);
                    }
                    return Status.OK_STATUS;
                } catch (OperationCancelled e) {
                    return Status.CANCEL_STATUS;
                } catch (Exception e) {
                    String reason = (e.getMessage() == null) ? e.toString() : e.getMessage();
                    VegadutaPlugin.logWarning("VegaDuta sign-in failed: " + reason, e);
                    if (onResult != null) {
                        onResult.accept(reason);
                    }
                    return Status.OK_STATUS; // reported through onResult, not as an error dialog
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Raised when the user cancels the progress Job. */
    private static final class OperationCancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void doSignIn(IProgressMonitor monitor) throws InterruptedException {
        String base = Environments.openidBase(VegadutaSettings.authBase(), Models.KEYCLOAK_REALM);

        monitor.beginTask("Requesting device code", IProgressMonitor.UNKNOWN);
        Map<String, String> startForm = new LinkedHashMap<>();
        startForm.put("client_id", Models.IDE_CLIENT_ID);
        // offline_access: realm SSO idle is 4h, offline tokens idle out at 30d.
        // An IDE closed overnight must still be signed in the next morning.
        startForm.put("scope", "openid offline_access");
        HttpResponse<String> startResponse = postForm(base + "/auth/device", startForm);
        DeviceFlow.Start start = DeviceFlow.parseStart(
                startResponse.statusCode(), startResponse.body(), System.currentTimeMillis());

        openInBrowser(start.verificationUri());
        monitor.subTask("Waiting for browser approval (code " + start.userCode() + ")");

        long intervalMs = start.intervalMs();
        for (;;) {
            checkCancelled(monitor);
            if (System.currentTimeMillis() > start.expiresAtMs()) {
                throw new ApiException(0, "The sign-in code expired. Please start again.");
            }
            Thread.sleep(intervalMs);
            checkCancelled(monitor);

            Map<String, String> pollForm = new LinkedHashMap<>();
            pollForm.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            pollForm.put("client_id", Models.IDE_CLIENT_ID);
            pollForm.put("device_code", start.deviceCode());
            HttpResponse<String> poll = postForm(base + "/token", pollForm);

            DeviceFlow.Poll outcome = DeviceFlow.classifyPoll(poll.statusCode(), poll.body());
            switch (outcome.kind()) {
                case SUCCESS -> {
                    TokenStore.get().storeTokenResponse(poll.body());
                    return;
                }
                case PENDING -> {
                    // keep polling at the same interval
                }
                case SLOW_DOWN -> intervalMs = DeviceFlow.nextIntervalMs(outcome.kind(), intervalMs, null);
                case RATE_LIMITED -> {
                    intervalMs = DeviceFlow.nextIntervalMs(
                        outcome.kind(), intervalMs, poll.headers().firstValue("Retry-After").orElse(null));
                    monitor.subTask("The sign-in server asked us to slow down - waiting "
                        + (intervalMs / 1000) + "s (code " + start.userCode() + ")");
                }
                case FAILED -> throw new ApiException(poll.statusCode(), outcome.message());
            }
        }
    }

    private static void checkCancelled(IProgressMonitor monitor) {
        if (monitor.isCanceled()) {
            throw new OperationCancelled();
        }
    }

    private static HttpResponse<String> postForm(String url, Map<String, String> fields) {
        try {
            return HTTP.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(DeviceFlow.formEncode(fields)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new ApiException(0, "Could not reach " + VegadutaSettings.authBase()
                    + " (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationCancelled();
        }
    }

    /** Opens a URL in the user's real browser - never inside the chat view: the
     * sign-in page must be somewhere the user can see the address bar.
     *
     * Hops to the UI thread, because this is called from the sign-in Job and
     * the workbench's browser support is UI-thread API. */
    public static void openInBrowser(String url) {
        Runnable open = () -> {
            try {
                PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(new URL(url));
            } catch (Exception e) {
                VegadutaPlugin.logWarning("Could not open " + url + " in a browser", e);
            }
        };
        try {
            PlatformUI.getWorkbench().getDisplay().syncExec(open);
        } catch (RuntimeException e) {
            VegadutaPlugin.logWarning("Could not reach the UI thread to open " + url, e);
        }
    }
}
