package com.example.omp.order.error;

/**
 * Thrown when a downstream service returns an unexpected error.
 *
 * <p>Translated to HTTP 502 by the global exception handler.
 */
public class DownstreamServiceException extends RuntimeException {

    private final String service;
    private final int status;

    public DownstreamServiceException(String service, int status, String message) {
        super("Downstream service '" + service + "' returned status " + status + ": " + message);
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
