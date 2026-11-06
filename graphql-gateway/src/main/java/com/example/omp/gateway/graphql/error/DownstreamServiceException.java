package com.example.omp.gateway.graphql.error;

public class DownstreamServiceException extends RuntimeException {
    private final String service;
    private final int status;

    public DownstreamServiceException(String service, int status, String message) {
        super(message);
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
