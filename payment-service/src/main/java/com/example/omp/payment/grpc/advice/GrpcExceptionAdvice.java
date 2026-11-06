package com.example.omp.payment.grpc.advice;

import com.example.omp.payment.error.DuplicateIdempotencyKeyException;
import com.example.omp.payment.error.PaymentNotFoundException;
import com.example.omp.payment.error.PaymentStateException;
import io.grpc.Status;
import net.devh.boot.grpc.server.advice.GrpcAdvice;
import net.devh.boot.grpc.server.advice.GrpcExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps domain exceptions to gRPC status codes.
 *
 * <p>Same pattern as inventory-service's advice. The mapping is documented in {@code
 * payment.proto}'s header comment.
 */
@GrpcAdvice
public class GrpcExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionAdvice.class);

    /** Observer error callbacks bypass annotation-based exception advice. */
    public static Status toStatus(Exception failure) {
        // Method-security SpEL evaluation can wrap a missing Authentication in an
        // IllegalArgumentException. Preserve its authentication status at the RPC boundary.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof org.springframework.security.core.AuthenticationException)
                return Status.UNAUTHENTICATED.withDescription("Authentication required");
        }
        if (failure instanceof org.springframework.security.access.AccessDeniedException) {
            return Status.PERMISSION_DENIED.withDescription("Access denied");
        }
        if (failure instanceof org.springframework.security.core.AuthenticationException) {
            return Status.UNAUTHENTICATED.withDescription("Authentication required");
        }
        if (failure instanceof PaymentNotFoundException)
            return Status.NOT_FOUND.withDescription("Payment not found");
        if (failure instanceof DuplicateIdempotencyKeyException)
            return Status.ALREADY_EXISTS.withDescription(
                    "Idempotency key conflicts with another request");
        if (failure instanceof org.springframework.dao.ConcurrencyFailureException)
            return Status.ABORTED.withDescription("Concurrent update; retry request");
        if (failure instanceof IllegalArgumentException)
            return Status.INVALID_ARGUMENT.withDescription("Invalid payment request");
        if (failure instanceof PaymentStateException || failure instanceof IllegalStateException)
            return Status.FAILED_PRECONDITION.withDescription(
                    "Payment operation conflicts with current state");
        log.error("Unexpected payment RPC failure", failure);
        return Status.INTERNAL.withDescription("Unexpected payment error");
    }

    @GrpcExceptionHandler(PaymentNotFoundException.class)
    public Status handleNotFound(PaymentNotFoundException e) {
        log.debug("→ NOT_FOUND: {}", e.getMessage());
        return Status.NOT_FOUND.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(PaymentStateException.class)
    public Status handleState(PaymentStateException e) {
        log.debug("→ FAILED_PRECONDITION: {}", e.getMessage());
        return Status.FAILED_PRECONDITION.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(DuplicateIdempotencyKeyException.class)
    public Status handleDuplicateKey(DuplicateIdempotencyKeyException e) {
        log.debug("→ ALREADY_EXISTS: {}", e.getMessage());
        return Status.ALREADY_EXISTS.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(IllegalStateException.class)
    public Status handleIllegalState(IllegalStateException e) {
        log.debug("→ FAILED_PRECONDITION: {}", e.getMessage());
        return Status.FAILED_PRECONDITION.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status handleIllegalArgument(IllegalArgumentException e) {
        log.debug("→ INVALID_ARGUMENT: {}", e.getMessage());
        return Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e);
    }

    @GrpcExceptionHandler(Exception.class)
    public Status handleUnexpected(Exception e) {
        log.error("Unexpected error in gRPC handler", e);
        return Status.INTERNAL
                .withDescription("An unexpected error occurred. Please retry.")
                .withCause(e);
    }
}
