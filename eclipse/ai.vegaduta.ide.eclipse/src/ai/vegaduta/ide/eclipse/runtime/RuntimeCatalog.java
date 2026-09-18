// The pinned catalog for the bundled on-device model runtime: one llama.cpp
// release (llama-server, CPU builds - the macOS builds include Metal) and a
// short list of GGUF models, each with its exact byte size and SHA-256.
//
// Nothing here is fetched from a "latest" pointer. A new llama.cpp release or
// model means editing this file, re-pinning the checksums, and shipping a new
// plugin version - so what a user downloads is always what was reviewed.
//
// Storage layout (shared with the JetBrains plugin on purpose, so a model
// downloaded in one IDE is reused by the other - keep it byte-for-byte):
//   <user.home>/.vegaduta/runtime/b11037-<os>-<arch>/   extracted llama-server
//   <user.home>/.vegaduta/models/<gguf file name>       the model file
// with os in windows|macos|linux and arch in x64|arm64.
//
// Pure Java: no Eclipse types, unit-tested in RuntimeCatalogTest.

package ai.vegaduta.ide.eclipse.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class RuntimeCatalog {

    /** The llama.cpp release every runtime archive below comes from. */
    public static final String LLAMA_CPP_RELEASE = "b11037";

    private static final String RELEASE_BASE =
            "https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_CPP_RELEASE + "/";
    private static final String HF_BASE = "https://huggingface.co/";

    /** One downloadable file with its pinned size and checksum. */
    public record Download(String fileName, String url, long sizeBytes, String sha256) {
    }

    /** A supported (os, arch) pair, in the storage layout's spelling. */
    public record Target(String os, String arch) {
        /** e.g. "b11037-windows-x64". */
        public String runtimeDirName() {
            return LLAMA_CPP_RELEASE + "-" + os + "-" + arch;
        }

        public boolean windows() {
            return "windows".equals(os);
        }
    }

    /** One model the runtime can serve. */
    public record Model(String id, String displayName, Download file, boolean recommended) {
    }

    private record RuntimeEntry(Target target, Download archive) {
    }

    private static final List<RuntimeEntry> RUNTIMES = List.of(
            runtime("windows", "x64", "llama-b11037-bin-win-cpu-x64.zip", 18439506L,
                    "cfa7eb5531fca78ca36dd4dd2555cc1deee0a062c404ab96de1b3fbe5fa313ae"),
            runtime("windows", "arm64", "llama-b11037-bin-win-cpu-arm64.zip", 12002382L,
                    "0c427a86f733ab9b1d0d40719d9f80dcdb45260f9aaf1062deca9a6d6029beda"),
            runtime("macos", "arm64", "llama-b11037-bin-macos-arm64.tar.gz", 11156604L,
                    "3573814427ef2df999adf74b43d55c6045bfb560b971305f3dffba36cd5de6b3"),
            runtime("macos", "x64", "llama-b11037-bin-macos-x64.tar.gz", 11205280L,
                    "7a4e253b95d028edfdf0d2e22479aedd62d1149a60052bf004dc881420bd2546"),
            runtime("linux", "x64", "llama-b11037-bin-ubuntu-x64.tar.gz", 16856513L,
                    "bb75ea4f1145899423a700871d608c81b9d93c13cfc306340e08437f3c2f9aa5"),
            runtime("linux", "arm64", "llama-b11037-bin-ubuntu-arm64.tar.gz", 13481275L,
                    "5edc41a4f1796f9e0193d904daf21024f59d89555bd063d244140ddd03d899e1"));

    /** Catalog order is display order; the recommended one comes first. */
    public static final List<Model> MODELS = List.of(
            model("qwen2.5-coder-1.5b", "Qwen2.5 Coder 1.5B, fast",
                    "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                    1117320768L, "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046", true),
            model("qwen2.5-coder-3b", "Qwen2.5 Coder 3B, balanced",
                    "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF", "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                    2104932800L, "724fb256bec1ff062b2f65e4569e871ad2e95ab2a3989723d1769c54294730b7", false),
            model("qwen2.5-coder-7b", "Qwen2.5 Coder 7B, best quality (16 GB RAM)",
                    "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF", "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                    4683073536L, "509287f78cb4d4cf6b3843734733b914b2c158e43e22a7f4bf5e963800894d3c", false));

    private RuntimeCatalog() {
    }

    private static RuntimeEntry runtime(String os, String arch, String asset, long size, String sha) {
        return new RuntimeEntry(new Target(os, arch), new Download(asset, RELEASE_BASE + asset, size, sha));
    }

    private static Model model(String id, String name, String repo, String file, long size, String sha,
            boolean recommended) {
        return new Model(id, name, new Download(file, HF_BASE + repo + "/resolve/main/" + file, size, sha),
                recommended);
    }

    // --- platform ------------------------------------------------------------

    /**
     * Maps JVM {@code os.name} / {@code os.arch} values to the storage
     * layout's (os, arch), or null when the pair is not one this catalog has a
     * build for (32-bit, FreeBSD, ...).
     */
    public static Target targetFor(String osName, String osArch) {
        String os = normaliseOs(osName);
        String arch = normaliseArch(osArch);
        if (os == null || arch == null) {
            return null;
        }
        return new Target(os, arch);
    }

    /** The target for the running JVM, or null when unsupported. */
    public static Target currentTarget() {
        return targetFor(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static String normaliseOs(String osName) {
        if (osName == null) {
            return null;
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return "windows";
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return "macos";
        }
        if (os.startsWith("linux")) {
            return "linux";
        }
        return null;
    }

    static String normaliseArch(String osArch) {
        if (osArch == null) {
            return null;
        }
        String arch = osArch.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64", "x86-64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
    }

    /** The runtime archive for {@code target}, or null when there is none. */
    public static Download runtimeFor(Target target) {
        if (target == null) {
            return null;
        }
        for (RuntimeEntry entry : RUNTIMES) {
            if (entry.target().equals(target)) {
                return entry.archive();
            }
        }
        return null;
    }

    /** The model with {@code id}, or null. */
    public static Model model(String id) {
        if (id == null) {
            return null;
        }
        for (Model model : MODELS) {
            if (model.id().equals(id)) {
                return model;
            }
        }
        return null;
    }

    public static Model recommended() {
        for (Model model : MODELS) {
            if (model.recommended()) {
                return model;
            }
        }
        return MODELS.get(0);
    }

    // --- storage layout ------------------------------------------------------

    /** {@code <home>/.vegaduta} */
    public static Path root(Path userHome) {
        return userHome.resolve(".vegaduta");
    }

    /** {@code <home>/.vegaduta/runtime/b11037-<os>-<arch>} */
    public static Path runtimeDir(Path userHome, Target target) {
        return root(userHome).resolve("runtime").resolve(target.runtimeDirName());
    }

    /** {@code <home>/.vegaduta/models} */
    public static Path modelsDir(Path userHome) {
        return root(userHome).resolve("models");
    }

    /** {@code <home>/.vegaduta/models/<gguf file name>} */
    public static Path modelFile(Path userHome, Model model) {
        return modelsDir(userHome).resolve(model.file().fileName());
    }

    /** "llama-server.exe" on Windows, "llama-server" elsewhere. */
    public static String serverBinaryName(Target target) {
        return target != null && target.windows() ? "llama-server.exe" : "llama-server";
    }
}
