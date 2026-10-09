package orderbook.api;

/** An API call refused before it reached the engine: HTTP {@code status} plus a machine-readable {@code reason}. */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String reason;

    public ApiException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}
