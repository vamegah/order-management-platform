package com.example.omp.customer.web;

import com.example.omp.customer.error.AddressNotFoundException;
import com.example.omp.customer.error.CustomerNotFoundException;
import com.example.omp.customer.error.EmailAlreadyExistsException;
import com.example.omp.customer.error.InvalidCustomerDataException;
import com.example.omp.shared.web.error.InvalidPaginationException;
import com.example.omp.shared.web.error.ProblemDetails;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for the web layer.
 *
 * <p>Maps domain and framework exceptions to {@link ProblemDetails} responses with appropriate HTTP
 * status codes.
 *
 * <p>Every error body includes a {@code traceId} (from Micrometer Tracing) for correlation. Clients
 * can quote it in support requests, and operators can search for it in Tempo/Loki.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ProblemDetails> malformedJson(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(factory.badRequest("Malformed JSON request", request));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ProblemDetails> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(factory.forbidden("Access denied", request));
    }

    @ExceptionHandler({
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ProblemDetails> invalidRequestParameter(
            Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(factory.badRequest("Invalid request parameters", request));
    }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailsFactory factory;

    public GlobalExceptionHandler(ProblemDetailsFactory factory) {
        this.factory = factory;
    }

    // -------------------------------------------------------------------------
    // 404 — Not Found
    // -------------------------------------------------------------------------

    @ExceptionHandler(CustomerNotFoundException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ProblemDetails> handleCustomerNotFound(
            CustomerNotFoundException ex, HttpServletRequest request) {
        log.debug("Customer not found: {}", ex.getIdentifier());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        factory.notFound(
                                HttpStatus.NOT_FOUND,
                                "Customer Not Found",
                                ex.getMessage(),
                                request));
    }

    @ExceptionHandler(AddressNotFoundException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ProblemDetails> handleAddressNotFound(
            AddressNotFoundException ex, HttpServletRequest request) {
        log.debug("Address not found: {}", ex.getIdentifier());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        factory.notFound(
                                HttpStatus.NOT_FOUND,
                                "Address Not Found",
                                ex.getMessage(),
                                request));
    }

    // -------------------------------------------------------------------------
    // 409 — Conflict
    // -------------------------------------------------------------------------

    @ExceptionHandler(EmailAlreadyExistsException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ProblemDetails> handleEmailConflict(
            EmailAlreadyExistsException ex, HttpServletRequest request) {
        log.debug("Email conflict: {}", ex.getEmail());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(factory.conflict(ex.getMessage(), request));
    }

    // -------------------------------------------------------------------------
    // 400 — Bad Request
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ProblemDetails> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.debug("Validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(factory.validationError(ex, request));
    }

    @ExceptionHandler(InvalidCustomerDataException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ProblemDetails> handleInvalidData(
            InvalidCustomerDataException ex, HttpServletRequest request) {
        log.debug("Invalid Customer data: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(factory.badRequest(ex.getMessage(), request));
    }

    @ExceptionHandler(InvalidPaginationException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ProblemDetails> handleInvalidPagination(
            InvalidPaginationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(factory.badRequest(ex.getMessage(), request));
    }

    // -------------------------------------------------------------------------
    // 500 — Internal Server Error
    // -------------------------------------------------------------------------
    // Catches any unhandled exception. Logs at ERROR (with trace ID) but
    // returns a generic message — we don't leak internal details to clients.
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ProblemDetails> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        factory.internalError(
                                "An unexpected error occurred. Please retry.", request));
    }
}
