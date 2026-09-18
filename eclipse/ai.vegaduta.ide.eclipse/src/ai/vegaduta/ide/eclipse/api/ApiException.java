// Mirrors ai.vegaduta.ide.api.ApiException (JetBrains) and ApiError
// (clients/shared/src/api/client.ts): an HTTP status plus the server's own
// message. status 0 means "no HTTP status" - a transport failure, or the
// mid-stream STREAM_ERROR_MARKER sentinel, where the status was already
// committed as 200 before the provider failed.

package ai.vegaduta.ide.eclipse.api;

public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
