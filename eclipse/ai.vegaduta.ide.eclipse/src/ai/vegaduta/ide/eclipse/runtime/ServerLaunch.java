// Starting llama-server and waiting until it can answer, plus tearing it (and
// anything it spawned) down again.
//
// Pure Java (java.lang.Process / ProcessHandle / java.net.http), no Eclipse
// types. The command line and environment are unit-tested
// (ServerLaunchTest); the process itself is not started by any test.

package ai.vegaduta.ide.eclipse.runtime;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ServerLaunch {

    /** Context window asked for; the Qwen2.5 Coder models support far more. */
    public static final int CONTEXT_TOKENS = 8192;
    public static final Duration HEALTH_DEADLINE = Duration.ofSeconds(180);
    private static final long POLL_MS = 500;

    private ServerLaunch() {
    }

    /**
     * {@code llama-server -m <model> --host 127.0.0.1 --port <port> -c 8192
     * --alias <modelId> --no-webui --no-slots}.
     *
     * The last two are hardening, not function: llama-server's built-in web
     * UI and its /slots monitoring endpoint (on by default, it reports what
     * each slot is working on) are not used by the plugin, and the server
     * answers any origin by default, so they are simply not offered.
     */
    public static List<String> command(Path binary, Path model, int port, String modelId) {
        List<String> command = new ArrayList<>();
        command.add(binary.toAbsolutePath().toString());
        command.add("-m");
        command.add(model.toAbsolutePath().toString());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("-c");
        command.add(Integer.toString(CONTEXT_TOKENS));
        command.add("--alias");
        command.add(modelId);
        command.add("--no-webui");
        command.add("--no-slots");
        return command;
    }

    public static String baseUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    /**
     * Adjusts the child environment: on Linux the extracted shared libraries
     * sit next to the binary, so its folder goes first on LD_LIBRARY_PATH.
     * (macOS builds find theirs through @loader_path; Windows searches the
     * executable's folder first.)
     */
    public static void adjustEnvironment(Map<String, String> env, RuntimeCatalog.Target target, Path binDir) {
        if (target != null && "linux".equals(target.os())) {
            String existing = env.get("LD_LIBRARY_PATH");
            String dir = binDir.toAbsolutePath().toString();
            env.put("LD_LIBRARY_PATH", existing == null || existing.isBlank() ? dir : dir + File.pathSeparator
                    + existing);
        }
    }

    /** Starts the server with its working directory at the binary's folder,
     * and pumps its combined output into {@code tail} on a daemon thread. */
    public static Process start(RuntimeCatalog.Target target, Path binary, Path model, int port, String modelId,
            RuntimeFiles.OutputTail tail) throws IOException {
        Path binDir = binary.toAbsolutePath().getParent();
        ProcessBuilder builder = new ProcessBuilder(command(binary, model, port, modelId));
        builder.directory(binDir.toFile());
        builder.redirectErrorStream(true);
        adjustEnvironment(builder.environment(), target, binDir);
        Process process = builder.start();
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                }
            } catch (IOException e) {
                // The process went away; the tail holds what it printed.
            }
        }, "vegaduta-llama-server-output");
        pump.setDaemon(true);
        pump.start();
        return process;
    }

    /** How waiting for the server ended. */
    public enum Health {
        READY, DIED, TIMED_OUT, CANCELLED
    }

    /**
     * Polls {@code /health} (200 = model loaded; 503 while loading), falling
     * back to {@code /v1/models} if /health is not served, until ready, the
     * process dies, the deadline passes, or {@code cancelled} turns true.
     */
    public static Health waitUntilReady(HttpClient http, String baseUrl, Process process, Duration deadline,
            BooleanSupplier cancelled) throws InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (cancelled.getAsBoolean()) {
                return Health.CANCELLED;
            }
            if (!process.isAlive()) {
                return Health.DIED;
            }
            int health = status(http, baseUrl + "/health");
            if (health == 200) {
                return Health.READY;
            }
            if (health == 404 && status(http, baseUrl + "/v1/models") == 200) {
                return Health.READY;
            }
            Thread.sleep(POLL_MS);
        }
        return Health.TIMED_OUT;
    }

    private static int status(HttpClient http, String url) throws InterruptedException {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (IOException e) {
            return -1; // not listening yet
        }
    }

    /** Ends the process and its descendants: politely, then forcibly. */
    public static void destroyTree(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> children = new ArrayList<>();
        try {
            process.descendants().forEach(children::add);
        } catch (RuntimeException e) {
            // Not permitted to list them; the process itself still goes.
        }
        for (ProcessHandle child : children) {
            child.destroy();
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        for (ProcessHandle child : children) {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}
