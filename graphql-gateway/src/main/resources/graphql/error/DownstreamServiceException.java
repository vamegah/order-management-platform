package com.example.omp.gateway.error;

/**
 * Thrown when a downstream service returns 5xx, times out, or the circuit
 * breaker for it is open.
 *
 * <p>Translated to a GraphQL error with code {@code DOWNSTREAM_SERVICE_ERROR}.
 *
 * <p>This is a server-fault error — the client's request was valid, but a
 * backend dependency failed. Clients should retry with backoff, or surface
 * the error to the user.
 *
 * <p>The {@code service} field identifies which downstream failed, useful
 * for logging and metrics.
 */
public class DownstreamServiceException extends RuntimeException {

    private final String service;
    private final int status;

    public DownstreamServiceException(String service, int status, String message) {
        super("Downstream service '" + service + "' returned status " + status + ": " + message);
        this.service = service;
        this.status = status;
    }

    public DownstreamServiceException(String service, int status, String message, Throwable cause) {
        super("Downstream service '" + service + "' returned status " + status + ": " + message, cause);
        this.service = service;
        this.status = status;
    }

    public String getService() {
        return service;
    }

    public int getStatus() {
        return status;
    }
}
