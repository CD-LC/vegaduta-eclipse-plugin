// The bundled on-device model runtime: one click downloads llama.cpp's
// llama-server and a GGUF model (both SHA-256 pinned in RuntimeCatalog),
// starts the server on 127.0.0.1, and wires it into the plugin's existing
// "Local inference server" settings.
//
// Why it exists: the SWT Browser has no WebGPU, so the shared chat page's
// in-browser engine (WebLLM) is pinned off in Eclipse. Without this, on-device
// AI in Eclipse meant installing Ollama or LM Studio by hand.
//
// Ownership and threading:
//  - One instance, created and shut down by VegadutaPlugin (bundle start/stop).
//  - Every download, extraction, hash and health poll runs in an Eclipse Job
//    (so the Progress view shows it too), never on the UI thread. The jobs
//    share one scheduling rule, so they run strictly one after another: a
//    new "Download & run" waits for the cancelled previous one to unwind.
//  - Only one server at a time. Each install/stop bumps a generation number;
//    a job whose generation is stale stops reporting and cleans up after
//    itself, so a superseded job can never overwrite a newer state.
//  - Listeners (the chat bridge, the preference page) get every status change
//    on the calling thread and must hop to the UI thread themselves.
//
// Private Mode does not gate downloads: a model download carries none of the
// person's content (protocol.ts runtime.install says the same).
//
// RULE 0 does not reach the 127.0.0.1 URL written here: it is the person's
// own process on their own machine, the same carve-out as the local inference
// server setting it fills in (see settings/Environments).

package ai.vegaduta.ide.eclipse.runtime;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

import ai.vegaduta.ide.eclipse.VegadutaPlugin;
import ai.vegaduta.ide.eclipse.runtime.RuntimeCatalog.Model;
import ai.vegaduta.ide.eclipse.runtime.RuntimeCatalog.Target;
import ai.vegaduta.ide.eclipse.runtime.RuntimeStatus.State;
import ai.vegaduta.ide.eclipse.settings.VegadutaSettings;

public final class LocalRuntime {

    /** Told about every state change. Called on whatever thread changed it. */
    public interface Listener {
        void statusChanged(RuntimeStatus status);

        /** The local-server settings were just (re)written or cleared. */
        default void settingsChanged() {
        }
    }

    /** All runtime jobs share this rule, so they never overlap. */
    private static final ISchedulingRule ONE_AT_A_TIME = new ISchedulingRule() {
        @Override
        public boolean contains(ISchedulingRule rule) {
            return rule == this;
        }

        @Override
        public boolean isConflicting(ISchedulingRule rule) {
            return rule == this;
        }
    };

    private static final String JOB_FAMILY = "ai.vegaduta.ide.eclipse.runtime";

    private final Path userHome;
    private final Target target;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    // All guarded by "this".
    private RuntimeStatus status;
    private int generation;
    private AtomicBoolean cancel = new AtomicBoolean();
    private Job job;
    private Process process;
    private boolean shutDown;

    public LocalRuntime() {
        this(Paths.get(System.getProperty("user.home")), RuntimeCatalog.currentTarget());
    }

    LocalRuntime(Path userHome, Target target) {
        this.userHome = userHome;
        this.target = target;
        this.status = RuntimeStatus.of(State.ABSENT);
    }

    // --- listeners / status --------------------------------------------------

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized RuntimeStatus status() {
        return status;
    }

    /** The runtime.status message for the current state. Reads a few file
     * sizes and markers (never hashes), so it is cheap. */
    public Map<String, Object> statusMessage() {
        return status().toMessage(RuntimeCatalog.MODELS, this::isModelInstalled);
    }

    /** Downloaded and verified (by marker) on this machine. */
    public boolean isModelInstalled(Model model) {
        return RuntimeFiles.looksVerified(RuntimeCatalog.modelFile(userHome, model), model.file());
    }

    /** False on an OS/arch with no pinned llama.cpp build. */
    public boolean isSupported() {
        return RuntimeCatalog.runtimeFor(target) != null;
    }

    private void publish(int gen, RuntimeStatus next) {
        synchronized (this) {
            if (gen != generation || shutDown) {
                return; // a newer install/stop owns the state now
            }
            status = next;
        }
        fireStatus(next);
    }

    private void fireStatus(RuntimeStatus next) {
        for (Listener listener : listeners) {
            try {
                listener.statusChanged(next);
            } catch (RuntimeException e) {
                VegadutaPlugin.logWarning("on-device model status listener failed", e);
            }
        }
    }

    private void fireSettingsChanged() {
        for (Listener listener : listeners) {
            try {
                listener.settingsChanged();
            } catch (RuntimeException e) {
                VegadutaPlugin.logWarning("on-device model settings listener failed", e);
            }
        }
    }

    // --- lifecycle -----------------------------------------------------------

    /**
     * Bundle start: works out the idle state, forgets a bundled URL left
     * behind by a previous session (its port is dead), and, if the person's
     * chosen model is installed and they did not stop it, starts it. All of
     * it in a background Job - bundle start must stay fast.
     */
    public void startUp() {
        Job startup = new Job("VegaDuta: checking the on-device model") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                if (RuntimeWiring.onStopped(VegadutaSettings.localServerSettings())) {
                    fireSettingsChanged();
                }
                Model chosen = RuntimeCatalog.model(VegadutaSettings.bundledModel());
                boolean installed = chosen != null && isModelInstalled(chosen) && runtimeInstalled();
                int gen;
                synchronized (LocalRuntime.this) {
                    gen = generation;
                }
                publish(gen, idleStatus(installed ? State.STOPPED : State.ABSENT, chosen));
                if (installed && VegadutaSettings.bundledAutoStart() && isSupported()) {
                    install(chosen.id());
                }
                return Status.OK_STATUS;
            }

            @Override
            public boolean belongsTo(Object family) {
                return JOB_FAMILY.equals(family);
            }
        };
        startup.setSystem(true);
        startup.setRule(ONE_AT_A_TIME);
        startup.schedule();
    }

    /** Bundle stop: cancel whatever runs and end the server and its children.
     * Settings are left alone; the next startUp() reconciles them. */
    public void shutdown() {
        Process running;
        Job current;
        synchronized (this) {
            shutDown = true;
            generation++;
            cancel.set(true);
            current = job;
            job = null;
            running = process;
            process = null;
        }
        if (current != null) {
            current.cancel();
        }
        ServerLaunch.destroyTree(running);
    }

    // --- install / stop ------------------------------------------------------

    /**
     * runtime.install: download what is missing (checksum-verified), then
     * start {@code modelId}, stopping any other first.
     */
    public void install(String modelId) {
        Model model = RuntimeCatalog.model(modelId);
        // A request that can only fail is answered WITHOUT touching what runs:
        // the error is told to listeners once, and the stored state (e.g. a
        // running server) stays as it is.
        if (model == null) {
            fireStatus(new RuntimeStatus(State.ERROR, modelId, null, "Unknown model \"" + modelId + "\".", null));
            return;
        }
        if (!isSupported()) {
            fireStatus(new RuntimeStatus(State.ERROR, model.id(), null,
                    "No on-device runtime is available for " + System.getProperty("os.name") + " ("
                            + System.getProperty("os.arch") + "). Supported: Windows, macOS and Linux on"
                            + " x64 or arm64.", null));
            return;
        }
        int gen;
        AtomicBoolean token;
        Process previous;
        Job previousJob;
        synchronized (this) {
            if (shutDown) {
                return;
            }
            generation++;
            gen = generation;
            cancel.set(true);
            cancel = new AtomicBoolean();
            token = cancel;
            previous = process;
            process = null;
            previousJob = job;
            job = null;
        }
        if (previousJob != null) {
            previousJob.cancel();
        }
        publish(gen, new RuntimeStatus(State.DOWNLOADING, model.id(), 0d, "Preparing", null));
        Job next = new Job("VegaDuta: on-device model " + model.displayName()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                VegadutaSettings.setBundledChoice(model.id(), true);
                if (previous != null) {
                    ServerLaunch.destroyTree(previous);
                    if (RuntimeWiring.onStopped(VegadutaSettings.localServerSettings())) {
                        fireSettingsChanged();
                    }
                }
                return runInstall(gen, token, model, monitor);
            }

            @Override
            protected void canceling() {
                token.set(true);
            }

            @Override
            public boolean belongsTo(Object family) {
                return JOB_FAMILY.equals(family);
            }
        };
        next.setRule(ONE_AT_A_TIME);
        synchronized (this) {
            if (gen != generation) {
                return;
            }
            job = next;
        }
        next.schedule();
    }

    /** runtime.stop: cancel a download in progress, or stop the server. */
    public void stop() {
        int gen;
        Process running;
        Job current;
        String modelId;
        synchronized (this) {
            if (shutDown) {
                return;
            }
            generation++;
            gen = generation;
            cancel.set(true);
            current = job;
            job = null;
            running = process;
            process = null;
            modelId = status.modelId();
        }
        if (current != null) {
            current.cancel();
        }
        // Answer at once; the job below settles stopped vs absent once any
        // cancelled download has unwound.
        publish(gen, new RuntimeStatus(State.STOPPED, modelId, null, null, null));
        Job stopper = new Job("VegaDuta: stopping the on-device model") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                // An explicit Stop also means "do not start it with Eclipse".
                VegadutaSettings.setBundledChoice(null, false);
                Model model = RuntimeCatalog.model(modelId != null ? modelId : VegadutaSettings.bundledModel());
                ServerLaunch.destroyTree(running);
                if (RuntimeWiring.onStopped(VegadutaSettings.localServerSettings())) {
                    fireSettingsChanged();
                }
                boolean installed = model != null && isModelInstalled(model);
                publish(gen, idleStatus(installed ? State.STOPPED : State.ABSENT, model));
                return Status.OK_STATUS;
            }
        };
        stopper.setSystem(true);
        // Same rule as the install job: the stop lands after a cancelled
        // install has unwound, so nothing it does can be undone by it.
        stopper.setRule(ONE_AT_A_TIME);
        stopper.schedule();
    }

    private static RuntimeStatus idleStatus(State state, Model model) {
        return new RuntimeStatus(state, model == null ? null : model.id(), null, null, null);
    }

    // --- the job -------------------------------------------------------------

    private IStatus runInstall(int gen, AtomicBoolean token, Model model, IProgressMonitor monitor) {
        monitor.beginTask("Getting " + model.displayName() + " ready", 1000);
        int[] worked = {0};
        try {
            Path binary = ensureRuntime(gen, token, model, monitor, worked);
            ensureModel(gen, token, model, monitor, worked);
            startServer(gen, token, model, binary);
            return Status.OK_STATUS;
        } catch (RuntimeFiles.CancelledException e) {
            return Status.CANCEL_STATUS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Status.CANCEL_STATUS;
        } catch (IOException | RuntimeException e) {
            if (!token.get()) {
                VegadutaPlugin.logWarning("on-device model setup failed", e);
                if (RuntimeWiring.onStopped(VegadutaSettings.localServerSettings())) {
                    fireSettingsChanged();
                }
                publish(gen, new RuntimeStatus(State.ERROR, model.id(), null,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null));
            }
            // OK, not ERROR: the failure is shown in the chat and the
            // preference page; an ERROR status would also pop a dialog.
            return Status.OK_STATUS;
        } finally {
            monitor.done();
        }
    }

    /** Ticks the Progress view so {@code fraction} of [from, to) is done. */
    private static void tick(IProgressMonitor monitor, int[] worked, int from, int to, double fraction) {
        int target = from + (int) Math.round((to - from) * fraction);
        if (target > worked[0]) {
            monitor.worked(target - worked[0]);
            worked[0] = target;
        }
    }

    /**
     * The extracted runtime is usable: llama-server is there, and the
     * directory's marker names this release's archive. A directory with the
     * binary but NO marker is adopted (and marked): the folder name already
     * pins release, OS and arch, and the JetBrains plugin may have extracted
     * it - replacing it could fail anyway while that IDE is running it.
     */
    private boolean runtimeInstalled() {
        RuntimeCatalog.Download archive = RuntimeCatalog.runtimeFor(target);
        if (archive == null) {
            return false;
        }
        Path dir = RuntimeCatalog.runtimeDir(userHome, target);
        try {
            if (RuntimeFiles.findServerBinary(dir, RuntimeCatalog.serverBinaryName(target)) == null) {
                return false;
            }
            Path marker = dir.resolve(RuntimeFiles.MARKER_SUFFIX);
            if (!Files.isRegularFile(marker)) {
                Files.writeString(marker, runtimeMarker(archive));
                return true;
            }
            return Files.readString(marker).trim().toLowerCase(java.util.Locale.ROOT)
                    .startsWith(archive.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    private static String runtimeMarker(RuntimeCatalog.Download archive) {
        return archive.sha256() + " " + archive.fileName() + "\n";
    }

    private Path ensureRuntime(int gen, AtomicBoolean token, Model model, IProgressMonitor monitor, int[] worked)
            throws IOException, InterruptedException {
        RuntimeCatalog.Download archive = RuntimeCatalog.runtimeFor(target);
        Path dir = RuntimeCatalog.runtimeDir(userHome, target);
        String binaryName = RuntimeCatalog.serverBinaryName(target);
        if (runtimeInstalled()) {
            tick(monitor, worked, 0, 50, 1);
            return RuntimeFiles.findServerBinary(dir, binaryName);
        }
        Path archiveFile = dir.resolveSibling(archive.fileName());
        if (!RuntimeFiles.verifyExisting(archiveFile, archive)) {
            monitor.subTask("Downloading the model runtime");
            RuntimeFiles.download(http, archive, archiveFile, (done, total) -> {
                publish(gen, new RuntimeStatus(State.DOWNLOADING, model.id(), RuntimeFiles.fraction(done, total),
                        RuntimeFiles.downloadDetail("the model runtime", done, total), null));
                tick(monitor, worked, 0, 40, RuntimeFiles.fraction(done, total));
            }, () -> token.get() || monitor.isCanceled());
        }
        checkCancelled(token, monitor);
        publish(gen, new RuntimeStatus(State.DOWNLOADING, model.id(), 1d, "Unpacking the model runtime", null));
        monitor.subTask("Unpacking the model runtime");
        Path staging = dir.resolveSibling(dir.getFileName() + ".extracting");
        RuntimeFiles.deleteTree(staging);
        if (archive.fileName().endsWith(".zip")) {
            RuntimeFiles.extractZip(archiveFile, staging);
        } else {
            RuntimeFiles.extractTarGz(archiveFile, staging);
        }
        RuntimeFiles.markExecutables(staging);
        if (RuntimeFiles.findServerBinary(staging, binaryName) == null) {
            RuntimeFiles.deleteTree(staging);
            throw new IOException(archive.fileName() + " does not contain " + binaryName + ".");
        }
        Files.writeString(staging.resolve(RuntimeFiles.MARKER_SUFFIX), runtimeMarker(archive));
        RuntimeFiles.deleteTree(dir);
        RuntimeFiles.moveIntoPlace(staging, dir);
        Files.deleteIfExists(archiveFile);
        Files.deleteIfExists(RuntimeFiles.markerFor(archiveFile));
        tick(monitor, worked, 0, 50, 1);
        return RuntimeFiles.findServerBinary(dir, binaryName);
    }

    private void ensureModel(int gen, AtomicBoolean token, Model model, IProgressMonitor monitor, int[] worked)
            throws IOException, InterruptedException {
        Path file = RuntimeCatalog.modelFile(userHome, model);
        if (RuntimeFiles.looksVerified(file, model.file())) {
            tick(monitor, worked, 50, 950, 1);
            return;
        }
        if (Files.isRegularFile(file)) {
            publish(gen, new RuntimeStatus(State.DOWNLOADING, model.id(), 0d, "Checking the model file", null));
            monitor.subTask("Checking the model file");
            if (RuntimeFiles.verifyExisting(file, model.file())) {
                tick(monitor, worked, 50, 950, 1);
                return;
            }
        }
        checkCancelled(token, monitor);
        monitor.subTask("Downloading " + model.displayName());
        RuntimeFiles.download(http, model.file(), file, (done, total) -> {
            publish(gen, new RuntimeStatus(State.DOWNLOADING, model.id(), RuntimeFiles.fraction(done, total),
                    RuntimeFiles.downloadDetail("the model", done, total), null));
            tick(monitor, worked, 50, 950, RuntimeFiles.fraction(done, total));
        }, () -> token.get() || monitor.isCanceled());
    }

    private static void checkCancelled(AtomicBoolean token, IProgressMonitor monitor)
            throws RuntimeFiles.CancelledException {
        if (token.get() || monitor.isCanceled()) {
            throw new RuntimeFiles.CancelledException();
        }
    }

    private void startServer(int gen, AtomicBoolean token, Model model, Path binary)
            throws IOException, InterruptedException {
        if (binary == null) {
            throw new IOException("The model runtime is missing llama-server.");
        }
        Path modelFile = RuntimeCatalog.modelFile(userHome, model);
        int port = RuntimeFiles.freePort();
        String baseUrl = ServerLaunch.baseUrl(port);
        RuntimeFiles.OutputTail tail = new RuntimeFiles.OutputTail(40);
        publish(gen, new RuntimeStatus(State.STARTING, model.id(), null, "Loading the model into memory", null));
        Process started = ServerLaunch.start(target, binary, modelFile, port, model.id(), tail);
        synchronized (this) {
            if (gen != generation) {
                ServerLaunch.destroyTree(started); // superseded while launching
                throw new RuntimeFiles.CancelledException();
            }
            process = started;
        }
        ServerLaunch.Health health = ServerLaunch.waitUntilReady(http, baseUrl, started,
                ServerLaunch.HEALTH_DEADLINE, token::get);
        if (health != ServerLaunch.Health.READY) {
            synchronized (this) {
                if (process == started) {
                    process = null;
                }
            }
            ServerLaunch.destroyTree(started);
            if (health == ServerLaunch.Health.CANCELLED) {
                throw new RuntimeFiles.CancelledException();
            }
            String why = health == ServerLaunch.Health.DIED
                    ? "The model server exited while starting."
                    : "The model server did not become ready within "
                            + ServerLaunch.HEALTH_DEADLINE.toSeconds() + " seconds.";
            String output = tail.text();
            throw new IOException(output.isBlank() ? why : why + "\n" + output);
        }
        boolean superseded;
        synchronized (this) {
            // Checked and wired under the lock, so a concurrent stop() either
            // happens first (no wiring) or after (its job unwires).
            superseded = gen != generation || process != started;
            if (!superseded) {
                RuntimeWiring.onRunning(VegadutaSettings.localServerSettings(), baseUrl, model.id());
            }
        }
        if (superseded) {
            ServerLaunch.destroyTree(started);
            throw new RuntimeFiles.CancelledException();
        }
        publish(gen, new RuntimeStatus(State.RUNNING, model.id(), null, "Serving " + model.displayName(),
                baseUrl));
        fireSettingsChanged();
        started.onExit().thenAccept(exited -> onServerExit(gen, started, model, tail));
    }

    /** The server went away on its own (crash, killed from outside). */
    private void onServerExit(int gen, Process exited, Model model, RuntimeFiles.OutputTail tail) {
        synchronized (this) {
            if (process != exited || gen != generation) {
                return; // we stopped it on purpose, or it was superseded
            }
            process = null;
        }
        if (RuntimeWiring.onStopped(VegadutaSettings.localServerSettings())) {
            fireSettingsChanged();
        }
        String output = tail.text();
        publish(gen, new RuntimeStatus(State.ERROR, model.id(), null,
                "The model server stopped unexpectedly (exit code " + exited.exitValue() + ")."
                        + (output.isBlank() ? "" : "\n" + output), null));
    }
}
