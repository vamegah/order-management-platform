package com.example.omp.inventory.grpc.advice;

import com.example.omp.inventory.error.InsufficientStockException;
import com.example.omp.inventory.error.InventoryNotFoundException;
import io.grpc.Status;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps domain exceptions to gRPC status codes.
 *
 * <p>Analogous to {@code @RestControllerAdvice} in Spring MVC. The framework invokes the
 * appropriate handler when a service method throws, then sends the returned {@link Status} to the
 * client.
 *
 * <p>The mapping is documented in {@code inventory.proto}'s header comment — clients rely on the
 * codes being consistent.
 */
@GrpcAdvice
public class GrpcExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionAdvice.class);

    @GrpcExceptionHandler(InventoryNotFoundException.class)
    public Status handleNotFound(InventoryNotFoundException e) {
        log.debug("Mapping InventoryNotFoundException to NOT_FOUND: {}", e.getMessage());
        return Status.NOT_FOUND.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(InsufficientStockException.class)
    public Status handleInsufficientStock(InsufficientStockException e) {
        log.debug("Mapping InsufficientStockException to FAILED_PRECONDITION: {}", e.getMessage());
        return Status.FAILED_PRECONDITION.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(IllegalStateException.class)
    public Status handleIllegalState(IllegalStateException e) {
        log.debug("Mapping IllegalStateException to FAILED_PRECONDITION: {}", e.getMessage());
        return Status.FAILED_PRECONDITION.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status handleIllegalArgument(IllegalArgumentException e) {
        log.debug("Mapping IllegalArgumentException to INVALID_ARGUMENT: {}", e.getMessage());
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e);
    }

    /**
     * Catch-all for unexpected exceptions. Returns INTERNAL so the client knows the request may be
     * retryable, but doesn't leak stack traces.
     */
    @GrpcExceptionHandler(Exception.class)
    public Status handleUnexpected(Exception e) {
        log.error("Unexpected error in gRPC handler", e);
        return Status.INTERNAL
                .withDescription("An unexpected error occurred. Please retry.")
                .withCause(e);
    }
}
