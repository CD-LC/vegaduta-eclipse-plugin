// File work for the bundled runtime: checksum-verified downloads, the
// ".sha256-ok" markers, archive extraction (zip-slip guarded), finding and
// marking llama-server executable, and picking a free loopback port.
//
// Pure Java - no Eclipse types - so all of it is unit-tested without a
// workbench (RuntimeFilesTest). The Eclipse Job, the process and the settings
// live in LocalRuntime.
//
// Markers: a verified file X gets a sibling "X.sha256-ok" holding
// "<sha256> <size>". A marker only vouches for a file of exactly that size:
// if the size on disk differs, the file is treated as unverified and hashed
// again (or re-downloaded). The extracted runtime directory carries its own
// ".sha256-ok" naming the archive it came from.

package ai.vegaduta.ide.eclipse.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RuntimeFiles {

    public static final String MARKER_SUFFIX = ".sha256-ok";
    public static final String PART_SUFFIX = ".part";

    private static final int BUFFER = 256 * 1024;

    private RuntimeFiles() {
    }

    /** The download was cancelled; the partial file is already deleted. */
    public static final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Cancelled.");
        }
    }

    /** The bytes did not match the pinned SHA-256; the file is deleted. */
    public static final class ChecksumMismatchException extends IOException {
        private static final long serialVersionUID = 1L;

        public ChecksumMismatchException(String message) {
            super(message);
        }
    }

    /** Called with bytes-so-far and the expected total. */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    // --- SHA-256 -------------------------------------------------------------

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /** Lower-case hex SHA-256 of a file's contents. */
    public static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Constant-case comparison of two hex digests; false for null. */
    public static boolean sameDigest(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    // --- markers -------------------------------------------------------------

    public static Path markerFor(Path file) {
        return file.resolveSibling(file.getFileName().toString() + MARKER_SUFFIX);
    }

    static String markerText(String sha256, long size) {
        return sha256.toLowerCase(Locale.ROOT) + " " + size + "\n";
    }

    public static void writeMarker(Path file, RuntimeCatalog.Download pin) throws IOException {
        Files.writeString(markerFor(file), markerText(pin.sha256(), pin.sizeBytes()), StandardCharsets.UTF_8);
    }

    /**
     * Cheap check, safe to call often: the file exists with the pinned size
     * AND its marker names the pinned checksum and size. Never hashes.
     */
    public static boolean looksVerified(Path file, RuntimeCatalog.Download pin) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != pin.sizeBytes()) {
                return false;
            }
            Path marker = markerFor(file);
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            return markerText(pin.sha256(), pin.sizeBytes()).trim()
                    .equals(Files.readString(marker, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The full check, for a background thread: true when the file is (now)
     * known good. A file of the pinned size with no marker - e.g. one another
     * IDE downloaded, or one copied in by hand - is hashed once and, when it
     * matches, gets its marker. A file of any other size is not trusted, and
     * its stale marker is removed.
     */
    public static boolean verifyExisting(Path file, RuntimeCatalog.Download pin) throws IOException {
        if (looksVerified(file, pin)) {
            return true;
        }
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (Files.size(file) != pin.sizeBytes()) {
            Files.deleteIfExists(markerFor(file));
            return false;
        }
        if (sameDigest(sha256Of(file), pin.sha256())) {
            writeMarker(file, pin);
            return true;
        }
        Files.deleteIfExists(markerFor(file));
        return false;
    }

    // --- download ------------------------------------------------------------

    /**
     * Streams {@code pin.url()} into {@code target + ".part"}, hashing as it
     * goes, reports progress roughly every 1%, checks the size and SHA-256
     * against the pin, then moves the file into place (atomically where the
     * file system allows) and writes its marker.
     *
     * The .part file is locked while it is written, so two IDEs sharing
     * ~/.vegaduta cannot interleave into it; the second one is told to wait.
     *
     * @throws CancelledException        when {@code cancelled} turns true.
     * @throws ChecksumMismatchException when the bytes are not the pinned ones.
     */
    public static void download(HttpClient http, RuntimeCatalog.Download pin, Path target, Progress progress,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName().toString() + PART_SUFFIX);
        HttpRequest request = HttpRequest.newBuilder(URI.create(pin.url()))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "VegaDuta-Eclipse")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + pin.fileName());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(pin.sizeBytes());
        MessageDigest digest = sha256();
        long done = 0;
        boolean complete = false;
        try (InputStream in = response.body();
                FileChannel out = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            FileLock lock = out.tryLock();
            if (lock == null) {
                throw new IOException("Another program is downloading " + pin.fileName()
                        + " into the same folder right now. Try again when it has finished.");
            }
            byte[] buffer = new byte[BUFFER];
            long step = Math.max(1, total / 100);
            long nextReport = 0;
            progress.update(0, total);
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new CancelledException();
                }
                digest.update(buffer, 0, read);
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                while (chunk.hasRemaining()) {
                    out.write(chunk);
                }
                done += read;
                if (done >= nextReport) {
                    progress.update(done, total);
                    nextReport = done + step;
                }
                if (done > pin.sizeBytes()) {
                    throw new ChecksumMismatchException(pin.fileName() + " is larger than expected ("
                            + done + " bytes) - download refused.");
                }
            }
            progress.update(done, total);
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(part);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (done != pin.sizeBytes() || !sameDigest(actual, pin.sha256())) {
            Files.deleteIfExists(part);
            throw new ChecksumMismatchException(pin.fileName() + " failed its checksum check (got " + done
                    + " bytes, SHA-256 " + actual + ") - the file was deleted. Try again; if it keeps"
                    + " failing, the download is being altered on the way.");
        }
        moveIntoPlace(part, target);
        writeMarker(target, pin);
    }

    /** Atomic rename where supported, a plain replace otherwise. */
    public static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // --- extraction ----------------------------------------------------------

    /**
     * Resolves an archive entry name under {@code destination}, refusing any
     * name that would land outside it ("zip-slip": "../x", "/etc/x",
     * "C:\\x", "a/../../x").
     */
    public static Path safeResolve(Path destination, String entryName) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        String name = entryName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Refusing archive entry with an absolute path: " + entryName);
        }
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Refusing archive entry outside the target folder: " + entryName);
        }
        return resolved;
    }

    /** Extracts a .zip with java.util.zip, every entry through safeResolve. */
    public static void extractZip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = safeResolve(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Extracts a .tar.gz with the system tar (present on macOS and Linux).
     * Both GNU tar and bsdtar refuse ".." members and strip leading "/" by
     * default, and the archive itself is checksum-pinned before this runs.
     */
    public static void extractTarGz(Path archive, Path destination) throws IOException, InterruptedException {
        Files.createDirectories(destination);
        ProcessBuilder builder = new ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "-C",
                destination.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("tar did not finish extracting " + archive.getFileName());
        }
        if (process.exitValue() != 0) {
            throw new IOException("tar could not extract " + archive.getFileName() + ": "
                    + new String(output, StandardCharsets.UTF_8).trim());
        }
    }

    /** The shallowest regular file called {@code binaryName} under root, or null. */
    public static Path findServerBinary(Path root, String binaryName) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName() != null && binaryName.equals(p.getFileName().toString()))
                    .filter(Files::isRegularFile)
                    .min(Comparator.comparingInt(Path::getNameCount))
                    .orElse(null);
        }
    }

    /**
     * True for files that must be executable after a unix extraction: the
     * llama.cpp programs (no extension) and shared libraries (.so, .so.N,
     * .dylib). Tar keeps modes already; this is for the case where it did not.
     */
    public static boolean needsExecBit(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".dylib") || name.endsWith(".so") || name.contains(".so.")) {
            return true;
        }
        return name.startsWith("llama-") && !name.contains(".");
    }

    /** Adds the execute bits on unix; a no-op where POSIX permissions are not
     * supported (Windows). */
    public static void markExecutables(Path root) throws IOException {
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> needsExecBit(p.getFileName().toString()))
                    .forEach(files::add);
        }
        for (Path file : files) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        }
    }

    /** Deletes a directory tree; missing is fine. */
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        paths.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    // --- ports ---------------------------------------------------------------

    /** A loopback port that was free a moment ago (bind to 0, read, close). */
    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // --- wording -------------------------------------------------------------

    /** "412 MB", "1.1 GB", "900 KB". Decimal units, as download sizes are shown. */
    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        }
        if (bytes >= 1_000_000L) {
            return Math.round(bytes / 1e6) + " MB";
        }
        return Math.max(0, Math.round(bytes / 1e3)) + " KB";
    }

    /** "Downloading the model - 412 MB of 1.1 GB". */
    public static String downloadDetail(String what, long done, long total) {
        return "Downloading " + what + " - " + formatBytes(done) + " of " + formatBytes(total);
    }

    /** 0..1, clamped; 0 for an unknown total. */
    public static double fraction(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, (double) done / total));
    }

    // --- process output ------------------------------------------------------

    /** The last {@code maxLines} lines a process printed, for error details. */
    public static final class OutputTail {
        private final int maxLines;
        private final Deque<String> lines = new ArrayDeque<>();

        public OutputTail(int maxLines) {
            this.maxLines = Math.max(1, maxLines);
        }

        public synchronized void add(String line) {
            if (line == null) {
                return;
            }
            lines.addLast(line.length() > 500 ? line.substring(0, 500) : line);
            while (lines.size() > maxLines) {
                lines.removeFirst();
            }
        }

        public synchronized String text() {
            return String.join("\n", lines);
        }
    }
}
