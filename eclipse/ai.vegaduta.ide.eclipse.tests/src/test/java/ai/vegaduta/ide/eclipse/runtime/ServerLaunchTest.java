// ServerLaunch: the exact llama-server command line and the child environment.
// No process is started here.

package ai.vegaduta.ide.eclipse.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServerLaunchTest {

    @Test
    @DisplayName("command: -m model, loopback host, the chosen port, -c 8192, --alias id, web UI and /slots off")
    void command() {
        Path binary = Paths.get("rt", "llama-server");
        Path model = Paths.get("models", "m.gguf");
        assertEquals(List.of(binary.toAbsolutePath().toString(), "-m", model.toAbsolutePath().toString(),
                "--host", "127.0.0.1", "--port", "50123", "-c", "8192", "--alias", "qwen2.5-coder-1.5b",
                "--no-webui", "--no-slots"),
                ServerLaunch.command(binary, model, 50123, "qwen2.5-coder-1.5b"));
        assertEquals("http://127.0.0.1:50123", ServerLaunch.baseUrl(50123));
    }

    @Test
    @DisplayName("Linux: the binary's folder goes first on LD_LIBRARY_PATH; other OSes are left alone")
    void environment() {
        Path bin = Paths.get("rt", "bin");
        String dir = bin.toAbsolutePath().toString();

        Map<String, String> fresh = new HashMap<>();
        ServerLaunch.adjustEnvironment(fresh, new RuntimeCatalog.Target("linux", "x64"), bin);
        assertEquals(dir, fresh.get("LD_LIBRARY_PATH"));

        Map<String, String> existing = new HashMap<>(Map.of("LD_LIBRARY_PATH", "/opt/lib"));
        ServerLaunch.adjustEnvironment(existing, new RuntimeCatalog.Target("linux", "arm64"), bin);
        assertEquals(dir + File.pathSeparator + "/opt/lib", existing.get("LD_LIBRARY_PATH"));

        Map<String, String> mac = new HashMap<>();
        ServerLaunch.adjustEnvironment(mac, new RuntimeCatalog.Target("macos", "arm64"), bin);
        assertFalse(mac.containsKey("LD_LIBRARY_PATH"));
    }
}
