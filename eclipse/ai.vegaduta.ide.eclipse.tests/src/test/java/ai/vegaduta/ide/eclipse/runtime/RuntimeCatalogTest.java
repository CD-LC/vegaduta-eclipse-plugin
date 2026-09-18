// RuntimeCatalog: platform -> pinned llama.cpp build, the model list, and the
// storage layout shared with the JetBrains plugin.

package ai.vegaduta.ide.eclipse.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuntimeCatalogTest {

    @Test
    @DisplayName("JVM os.name / os.arch map to the storage layout's os and arch")
    void platformMapping() {
        assertEquals(new RuntimeCatalog.Target("windows", "x64"), RuntimeCatalog.targetFor("Windows 11", "amd64"));
        assertEquals(new RuntimeCatalog.Target("windows", "arm64"),
                RuntimeCatalog.targetFor("Windows 11", "aarch64"));
        assertEquals(new RuntimeCatalog.Target("macos", "arm64"), RuntimeCatalog.targetFor("Mac OS X", "aarch64"));
        assertEquals(new RuntimeCatalog.Target("macos", "x64"), RuntimeCatalog.targetFor("Mac OS X", "x86_64"));
        assertEquals(new RuntimeCatalog.Target("linux", "x64"), RuntimeCatalog.targetFor("Linux", "amd64"));
        assertEquals(new RuntimeCatalog.Target("linux", "arm64"), RuntimeCatalog.targetFor("Linux", "aarch64"));
    }

    @Test
    @DisplayName("unsupported platforms get no target and no runtime")
    void unsupportedPlatforms() {
        assertNull(RuntimeCatalog.targetFor("FreeBSD", "amd64"));
        assertNull(RuntimeCatalog.targetFor("SunOS", "sparcv9"));
        assertNull(RuntimeCatalog.targetFor("Windows 10", "x86"));
        assertNull(RuntimeCatalog.targetFor("Linux", "arm"));
        assertNull(RuntimeCatalog.targetFor("Linux", "ppc64le"));
        assertNull(RuntimeCatalog.targetFor(null, "amd64"));
        assertNull(RuntimeCatalog.targetFor("Linux", null));
        assertNull(RuntimeCatalog.runtimeFor(null));
        assertNull(RuntimeCatalog.runtimeFor(new RuntimeCatalog.Target("freebsd", "x64")));
    }

    @Test
    @DisplayName("each supported platform selects its exact pinned b11037 CPU archive")
    void runtimeSelection() {
        assertRuntime("Windows 11", "amd64", "llama-b11037-bin-win-cpu-x64.zip", 18439506L,
                "cfa7eb5531fca78ca36dd4dd2555cc1deee0a062c404ab96de1b3fbe5fa313ae");
        assertRuntime("Windows 11", "aarch64", "llama-b11037-bin-win-cpu-arm64.zip", 12002382L,
                "0c427a86f733ab9b1d0d40719d9f80dcdb45260f9aaf1062deca9a6d6029beda");
        assertRuntime("Mac OS X", "aarch64", "llama-b11037-bin-macos-arm64.tar.gz", 11156604L,
                "3573814427ef2df999adf74b43d55c6045bfb560b971305f3dffba36cd5de6b3");
        assertRuntime("Mac OS X", "x86_64", "llama-b11037-bin-macos-x64.tar.gz", 11205280L,
                "7a4e253b95d028edfdf0d2e22479aedd62d1149a60052bf004dc881420bd2546");
        assertRuntime("Linux", "amd64", "llama-b11037-bin-ubuntu-x64.tar.gz", 16856513L,
                "bb75ea4f1145899423a700871d608c81b9d93c13cfc306340e08437f3c2f9aa5");
        assertRuntime("Linux", "aarch64", "llama-b11037-bin-ubuntu-arm64.tar.gz", 13481275L,
                "5edc41a4f1796f9e0193d904daf21024f59d89555bd063d244140ddd03d899e1");
    }

    private static void assertRuntime(String os, String arch, String asset, long size, String sha) {
        RuntimeCatalog.Download download = RuntimeCatalog.runtimeFor(RuntimeCatalog.targetFor(os, arch));
        assertNotNull(download, os + "/" + arch);
        assertEquals(asset, download.fileName());
        assertEquals("https://github.com/ggml-org/llama.cpp/releases/download/b11037/" + asset, download.url());
        assertEquals(size, download.sizeBytes());
        assertEquals(sha, download.sha256());
    }

    @Test
    @DisplayName("models: three pinned Qwen2.5 Coder GGUFs, 1.5B recommended, Hugging Face resolve URLs")
    void models() {
        assertEquals(List.of("qwen2.5-coder-1.5b", "qwen2.5-coder-3b", "qwen2.5-coder-7b"),
                RuntimeCatalog.MODELS.stream().map(RuntimeCatalog.Model::id).toList());
        assertEquals("qwen2.5-coder-1.5b", RuntimeCatalog.recommended().id());
        assertEquals(1, RuntimeCatalog.MODELS.stream().filter(RuntimeCatalog.Model::recommended).count());
        RuntimeCatalog.Model small = RuntimeCatalog.model("qwen2.5-coder-1.5b");
        assertEquals("https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/"
                + "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", small.file().url());
        assertEquals(1117320768L, small.file().sizeBytes());
        assertEquals("cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046", small.file().sha256());
        assertEquals(4683073536L, RuntimeCatalog.model("qwen2.5-coder-7b").file().sizeBytes());
        assertNull(RuntimeCatalog.model("gpt-5"));
        assertNull(RuntimeCatalog.model(null));
    }

    @Test
    @DisplayName("every pin is a 64-char lower-case hex SHA-256, and no two are the same")
    void pinsAreWellFormed() {
        Set<String> seen = new HashSet<>();
        for (RuntimeCatalog.Model model : RuntimeCatalog.MODELS) {
            assertTrue(model.file().sha256().matches("[0-9a-f]{64}"), model.id());
            assertTrue(seen.add(model.file().sha256()));
        }
        for (String os : List.of("windows", "macos", "linux")) {
            for (String arch : List.of("x64", "arm64")) {
                RuntimeCatalog.Download d = RuntimeCatalog.runtimeFor(new RuntimeCatalog.Target(os, arch));
                assertNotNull(d, os + "-" + arch);
                assertTrue(d.sha256().matches("[0-9a-f]{64}"), d.fileName());
                assertTrue(seen.add(d.sha256()));
            }
        }
    }

    @Test
    @DisplayName("storage layout: ~/.vegaduta/runtime/b11037-<os>-<arch> and ~/.vegaduta/models/<file>")
    void storageLayout() {
        Path home = Paths.get("home-dir");
        RuntimeCatalog.Target target = new RuntimeCatalog.Target("linux", "arm64");
        assertEquals(home.resolve(".vegaduta").resolve("runtime").resolve("b11037-linux-arm64"),
                RuntimeCatalog.runtimeDir(home, target));
        assertEquals(home.resolve(".vegaduta").resolve("models").resolve("qwen2.5-coder-3b-instruct-q4_k_m.gguf"),
                RuntimeCatalog.modelFile(home, RuntimeCatalog.model("qwen2.5-coder-3b")));
        assertEquals("llama-server.exe", RuntimeCatalog.serverBinaryName(new RuntimeCatalog.Target("windows", "x64")));
        assertEquals("llama-server", RuntimeCatalog.serverBinaryName(target));
        assertFalse(target.windows());
    }
}
