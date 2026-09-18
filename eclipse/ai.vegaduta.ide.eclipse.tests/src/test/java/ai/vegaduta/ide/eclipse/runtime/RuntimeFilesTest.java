// RuntimeFiles: SHA-256 verification and markers, the download path against a
// tiny in-process HTTP server (no real large downloads), zip-slip rejection,
// binary discovery, the exec-bit rule, free ports and the wording.

package ai.vegaduta.ide.eclipse.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class RuntimeFilesTest {

    @TempDir
    Path dir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);
    /** FIPS 180-2 test vector for "abc". */
    private static final String ABC_SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static RuntimeCatalog.Download pin(String name, long size, String sha) {
        return new RuntimeCatalog.Download(name, "http://unused/" + name, size, sha);
    }

    // --- SHA-256 / markers ---------------------------------------------------

    @Test
    @DisplayName("sha256Of matches the FIPS test vector; digests compare case-insensitively")
    void sha256() throws IOException {
        Path file = Files.write(dir.resolve("abc.bin"), ABC);
        assertEquals(ABC_SHA, RuntimeFiles.sha256Of(file));
        assertTrue(RuntimeFiles.sameDigest(ABC_SHA.toUpperCase(), ABC_SHA));
        assertFalse(RuntimeFiles.sameDigest(null, ABC_SHA));
    }

    @Test
    @DisplayName("verifyExisting: a matching file is hashed once and gets a marker; looksVerified then trusts it")
    void verifyAndMark() throws IOException {
        Path file = Files.write(dir.resolve("model.gguf"), ABC);
        RuntimeCatalog.Download pin = pin("model.gguf", 3, ABC_SHA);
        assertFalse(RuntimeFiles.looksVerified(file, pin), "no marker yet");
        assertTrue(RuntimeFiles.verifyExisting(file, pin));
        assertTrue(Files.isRegularFile(dir.resolve("model.gguf.sha256-ok")));
        assertTrue(RuntimeFiles.looksVerified(file, pin));
    }

    @Test
    @DisplayName("verifyExisting: wrong bytes of the right size are refused")
    void verifyRejectsWrongBytes() throws IOException {
        Path file = Files.write(dir.resolve("model.gguf"), "abd".getBytes(StandardCharsets.US_ASCII));
        RuntimeCatalog.Download pin = pin("model.gguf", 3, ABC_SHA);
        assertFalse(RuntimeFiles.verifyExisting(file, pin));
        assertFalse(Files.exists(RuntimeFiles.markerFor(file)));
    }

    @Test
    @DisplayName("a marker never vouches for a file whose size changed: it is dropped and the file re-checked")
    void sizeChangeInvalidatesMarker() throws IOException {
        Path file = Files.write(dir.resolve("model.gguf"), ABC);
        RuntimeCatalog.Download pin = pin("model.gguf", 3, ABC_SHA);
        assertTrue(RuntimeFiles.verifyExisting(file, pin));
        Files.write(file, "abcd".getBytes(StandardCharsets.US_ASCII)); // truncated/extended by someone
        assertFalse(RuntimeFiles.looksVerified(file, pin));
        assertFalse(RuntimeFiles.verifyExisting(file, pin));
        assertFalse(Files.exists(RuntimeFiles.markerFor(file)));
    }

    @Test
    @DisplayName("a marker naming a different checksum is not trusted")
    void markerForAnotherPin() throws IOException {
        Path file = Files.write(dir.resolve("model.gguf"), ABC);
        RuntimeFiles.writeMarker(file, pin("model.gguf", 3, "0".repeat(64)));
        assertFalse(RuntimeFiles.looksVerified(file, pin("model.gguf", 3, ABC_SHA)));
    }

    // --- download ------------------------------------------------------------

    private String serve(byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/file");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Test
    @DisplayName("download: follows redirects, reports progress, verifies, moves into place, writes the marker")
    void downloadVerifies() throws Exception {
        byte[] body = new byte[300_000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31);
        }
        Path source = Files.write(dir.resolve("source.bin"), body);
        String sha = RuntimeFiles.sha256Of(source);
        String base = serve(body);
        Path target = dir.resolve("out").resolve("model.gguf");
        List<Long> reports = new ArrayList<>();
        RuntimeCatalog.Download pin = new RuntimeCatalog.Download("model.gguf", base + "/redirect", body.length, sha);
        RuntimeFiles.download(client(), pin, target, (done, total) -> reports.add(done), () -> false);
        assertEquals(sha, RuntimeFiles.sha256Of(target));
        assertTrue(RuntimeFiles.looksVerified(target, pin));
        assertFalse(Files.exists(target.resolveSibling("model.gguf.part")));
        assertEquals(0L, reports.get(0));
        assertEquals((long) body.length, reports.get(reports.size() - 1));
        assertTrue(reports.size() <= 110, "about every 1%, not every read: " + reports.size());
    }

    @Test
    @DisplayName("download: a checksum mismatch deletes the file and reports it")
    void downloadMismatch() throws Exception {
        String base = serve(ABC);
        Path target = dir.resolve("model.gguf");
        RuntimeCatalog.Download pin = new RuntimeCatalog.Download("model.gguf", base + "/file", 3, "0".repeat(64));
        IOException error = assertThrows(RuntimeFiles.ChecksumMismatchException.class,
                () -> RuntimeFiles.download(client(), pin, target, (d, t) -> { }, () -> false));
        assertTrue(error.getMessage().contains("checksum"));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("model.gguf.part")));
        assertFalse(Files.exists(RuntimeFiles.markerFor(target)));
    }

    @Test
    @DisplayName("download: a body longer than the pin is refused, not written to the end")
    void downloadTooLong() throws Exception {
        String base = serve(new byte[1000]);
        Path target = dir.resolve("model.gguf");
        RuntimeCatalog.Download pin = new RuntimeCatalog.Download("model.gguf", base + "/file", 10, ABC_SHA);
        assertThrows(RuntimeFiles.ChecksumMismatchException.class,
                () -> RuntimeFiles.download(client(), pin, target, (d, t) -> { }, () -> false));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("model.gguf.part")));
    }

    @Test
    @DisplayName("download: cancelling stops it and removes the partial file")
    void downloadCancel() throws Exception {
        String base = serve(new byte[600_000]);
        Path target = dir.resolve("model.gguf");
        RuntimeCatalog.Download pin = new RuntimeCatalog.Download("model.gguf", base + "/file", 600_000,
                ABC_SHA);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeFiles.CancelledException.class,
                () -> RuntimeFiles.download(client(), pin, target, (d, t) -> { }, () -> calls.incrementAndGet() > 1));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("model.gguf.part")));
    }

    @Test
    @DisplayName("download: an HTTP error is a plain failure naming the status")
    void downloadHttpError() throws Exception {
        String base = serve(ABC);
        RuntimeCatalog.Download pin = new RuntimeCatalog.Download("model.gguf", base + "/missing", 3, ABC_SHA);
        IOException error = assertThrows(IOException.class,
                () -> RuntimeFiles.download(client(), pin, dir.resolve("model.gguf"), (d, t) -> { }, () -> false));
        assertTrue(error.getMessage().contains("404"), error.getMessage());
    }

    // --- extraction ----------------------------------------------------------

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("zip: nested entries extract, and llama-server.exe is found at the shallowest depth")
    void zipExtracts() throws IOException {
        Path archive = Files.write(dir.resolve("rt.zip"), zip(
                "build/bin/llama-server.exe", "exe",
                "build/bin/ggml.dll", "dll",
                "build/bin/deeper/llama-server.exe", "decoy"));
        Path out = dir.resolve("out");
        RuntimeFiles.extractZip(archive, out);
        Path binary = RuntimeFiles.findServerBinary(out, "llama-server.exe");
        assertEquals(out.resolve("build/bin/llama-server.exe").toAbsolutePath().normalize(),
                binary.toAbsolutePath().normalize());
        assertEquals("dll", Files.readString(out.resolve("build/bin/ggml.dll")));
        assertNull(RuntimeFiles.findServerBinary(out, "llama-server"));
        assertNull(RuntimeFiles.findServerBinary(dir.resolve("nowhere"), "llama-server"));
    }

    @Test
    @DisplayName("zip-slip: an entry escaping the target folder is refused and nothing lands outside it")
    void zipSlipRejected() throws IOException {
        Path archive = Files.write(dir.resolve("evil.zip"), zip("ok.txt", "fine", "../escaped.txt", "pwned"));
        Path out = dir.resolve("out");
        IOException error = assertThrows(IOException.class, () -> RuntimeFiles.extractZip(archive, out));
        assertTrue(error.getMessage().contains("../escaped.txt"), error.getMessage());
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    @DisplayName("safeResolve refuses .., absolute, drive-letter and backslash tricks; allows plain names")
    void safeResolve() throws IOException {
        Path out = dir.resolve("out");
        for (String bad : List.of("../x", "a/../../x", "/etc/passwd", "\\\\server\\share\\x", "C:\\x", "c:x",
                "..\\..\\x", "a/b/../../../x")) {
            assertThrows(IOException.class, () -> RuntimeFiles.safeResolve(out, bad), bad);
        }
        assertEquals(out.toAbsolutePath().normalize().resolve("a").resolve("b.txt"),
                RuntimeFiles.safeResolve(out, "a/./c/../b.txt"));
    }

    @Test
    @DisplayName("exec bit: llama-* programs and shared libraries, not headers, licences or .exe")
    void execBitRule() {
        for (String yes : List.of("llama-server", "llama-cli", "libllama.so", "libggml.so.0", "libggml-base.dylib")) {
            assertTrue(RuntimeFiles.needsExecBit(yes), yes);
        }
        for (String no : List.of("LICENSE", "README.md", "llama.h", "llama-server.exe", "ggml.dll", "notes.txt")) {
            assertFalse(RuntimeFiles.needsExecBit(no), no);
        }
    }

    // --- ports / wording -----------------------------------------------------

    @Test
    @DisplayName("freePort returns a bindable loopback port")
    void freePort() throws IOException {
        int port = RuntimeFiles.freePort();
        assertTrue(port > 0 && port < 65536, "port " + port);
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(port, socket.getLocalPort());
        }
    }

    @Test
    @DisplayName("sizes and the download detail read as the protocol example does")
    void wording() {
        assertEquals("Downloading the model - 412 MB of 1.1 GB",
                RuntimeFiles.downloadDetail("the model", 412_000_000L, 1_117_320_768L));
        assertEquals("900 KB", RuntimeFiles.formatBytes(900_000));
        assertEquals("18 MB", RuntimeFiles.formatBytes(18_439_506));
        assertEquals("4.7 GB", RuntimeFiles.formatBytes(4_683_073_536L));
        assertEquals(0.5, RuntimeFiles.fraction(5, 10));
        assertEquals(0.0, RuntimeFiles.fraction(5, 0));
        assertEquals(1.0, RuntimeFiles.fraction(20, 10));
    }

    @Test
    @DisplayName("OutputTail keeps only the last lines")
    void outputTail() {
        RuntimeFiles.OutputTail tail = new RuntimeFiles.OutputTail(2);
        tail.add("one");
        tail.add("two");
        tail.add("three");
        assertEquals("two\nthree", tail.text());
    }
}
