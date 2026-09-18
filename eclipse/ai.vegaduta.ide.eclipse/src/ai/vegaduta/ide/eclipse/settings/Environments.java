// Pure environment -> URL resolution. No Eclipse API, so it is unit-tested
// directly (EnvironmentsTest).
//
// RULE 0 (CLAUDE.md): vegaduta.ai is PRODUCTION, vegaduta.xyz is staging/QA,
// and NO browser-facing or token-issuer URL may point at localhost /
// 127.0.0.1. There is therefore no localhost default anywhere in this file,
// and a hand-typed custom base that resolves to a loopback host is refused
// rather than used - the auth base is a token issuer, which is precisely what
// RULE 0 names. Mirrors clients/jetbrains/.../settings/VegadutaSettingsState
// and ENVIRONMENTS in clients/shared/src/api/types.ts.
//
// The ONE address in this plugin that may be a loopback URL is the user's own
// local inference server (Ollama / LM Studio / llama.cpp). That is their own
// process on their own machine - not a platform URL and not a token issuer -
// so it is handled separately, in VegadutaSettings.localServerBaseUrl(), and
// has no default at all: blank means local inference is off.

package ai.vegaduta.ide.eclipse.settings;

import java.net.URI;
import java.util.Locale;

public final class Environments {

    public static final String ENV_PRODUCTION = "production";
    public static final String ENV_STAGING = "staging";
    public static final String ENV_CUSTOM = "custom";

    public static final String PRODUCTION_API = "https://api.vegaduta.ai";
    public static final String PRODUCTION_AUTH = "https://auth.vegaduta.ai";
    public static final String STAGING_API = "https://api.vegaduta.xyz";
    public static final String STAGING_AUTH = "https://auth.vegaduta.xyz";

    private Environments() {
    }

    /**
     * The API base for an environment. Anything that is not "production" or
     * "custom" resolves to staging - an unrecognised persisted value must land
     * on the SAFE environment, never on production.
     */
    public static String apiBase(String environment, String customApiBase) {
        if (ENV_PRODUCTION.equals(environment)) {
            return PRODUCTION_API;
        }
        if (ENV_CUSTOM.equals(environment)) {
            return usableCustom(customApiBase, STAGING_API);
        }
        return STAGING_API;
    }

    /** The Keycloak base for an environment. Same fallback rule as apiBase. */
    public static String authBase(String environment, String customAuthBase) {
        if (ENV_PRODUCTION.equals(environment)) {
            return PRODUCTION_AUTH;
        }
        if (ENV_CUSTOM.equals(environment)) {
            return usableCustom(customAuthBase, STAGING_AUTH);
        }
        return STAGING_AUTH;
    }

    private static String usableCustom(String candidate, String fallback) {
        String trimmed = trimTrailingSlashes(candidate);
        if (trimmed.isBlank() || isLoopback(trimmed)) {
            return fallback;
        }
        return trimmed;
    }

    /** Trailing slashes removed and whitespace trimmed; null becomes "". */
    public static String trimTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }

    /**
     * True when a URL names the local machine. RULE 0's test for a PLATFORM
     * URL (api / auth base): such a URL must never be one of these. It is
     * deliberately NOT applied to the local inference server setting.
     *
     * A string this cannot parse is reported as NOT loopback: refusing an
     * unparseable custom base would silently reroute a self-hosted install to
     * staging, which is worse than letting the HTTP call fail visibly.
     */
    public static boolean isLoopback(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String host;
        try {
            URI uri = URI.create(url.trim());
            host = uri.getHost();
            if (host == null) {
                // "localhost:8080" with no scheme parses as an opaque URI.
                String raw = url.trim().toLowerCase(Locale.ROOT);
                int slash = raw.indexOf("//");
                if (slash >= 0) {
                    raw = raw.substring(slash + 2);
                }
                int cut = raw.indexOf('/');
                if (cut >= 0) {
                    raw = raw.substring(0, cut);
                }
                int colon = raw.lastIndexOf(':');
                host = (colon > 0) ? raw.substring(0, colon) : raw;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.equals("localhost")
                || h.equals("127.0.0.1")
                || h.startsWith("127.")
                || h.equals("::1")
                || h.equals("0.0.0.0")
                || h.endsWith(".localhost");
    }

    /** The realm-scoped OIDC base every auth call hangs off. */
    public static String openidBase(String authBase, String realm) {
        return trimTrailingSlashes(authBase) + "/realms/" + realm + "/protocol/openid-connect";
    }
}
