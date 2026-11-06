package com.example.omp.order.web;

import com.example.omp.order.error.*;
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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemDetailsFactory factory;

    public GlobalExceptionHandler(ProblemDetailsFactory factory) {
        this.factory = factory;
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetails> forbidden(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.forbidden("Access denied", request));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetails> malformedJson(HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.badRequest("Malformed JSON request", request));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetails> unsupportedMediaType(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.unsupportedMediaType("Expected a supported JSON request", request));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetails> handleConcurrentUpdate(
            org.springframework.dao.OptimisticLockingFailureException ex,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.conflict("Order changed concurrently. Reload and retry.", request));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetails> handleOrderNotFound(
            OrderNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        factory.notFound(
                                HttpStatus.NOT_FOUND, "Order Not Found", ex.getMessage(), request));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ProblemDetails> handleCustomerNotFound(
            CustomerNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        factory.notFound(
                                HttpStatus.NOT_FOUND,
                                "Customer Not Found",
                                ex.getMessage(),
                                request));
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ProblemDetails> handleInvalidState(
            InvalidOrderStateException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.conflict(ex.getMessage(), request));
    }

    @ExceptionHandler({InvalidOrderDataException.class, ProductUnavailableException.class})
    public ResponseEntity<ProblemDetails> handleInvalidData(
            RuntimeException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.badRequest(ex.getMessage(), request));
    }

    @ExceptionHandler(OrderAccessDeniedException.class)
    public ResponseEntity<ProblemDetails> handleAccessDenied(
            OrderAccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.forbidden("Access to this order was denied.", request));
    }

    @ExceptionHandler(DownstreamServiceException.class)
    public ResponseEntity<ProblemDetails> handleDownstream(
            DownstreamServiceException ex, HttpServletRequest request) {
        log.warn("Downstream failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(
                        factory.badGateway(
                                "A downstream service could not complete the request.", request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetails> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.validationError(ex, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetails> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.internalError("An unexpected error occurred.", request));
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ProblemDetails> handleInvalidPagination(
            InvalidPaginationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(factory.badRequest(ex.getMessage(), request));
    }
}
