package com.example.omp.adapter.rest;

import com.example.omp.adapter.port.BankingException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class BankingExceptionHandler
        extends org.springframework.web.servlet.mvc.method.annotation
                .ResponseEntityExceptionHandler {

    @ExceptionHandler(com.example.omp.adapter.idempotency.IdempotencyConflictException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleIdempotencyConflict() {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "Idempotency key reused with different payload");
        problem.setType(URI.create("urn:problem:idempotency-conflict"));
        problem.setTitle("IDEMPOTENCY_CONFLICT");
        return problem;
    }

    @ExceptionHandler(BankingException.class)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid banking request",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Account not found",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "Banking request declined",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Daily banking limit exceeded",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "502",
            description = "Invalid upstream banking response",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Banking service unavailable",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Conflicting transfer identity",
            useReturnTypeSchema = true)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "504",
            description = "Bank response deadline exceeded",
            useReturnTypeSchema = true)
    public ProblemDetail handle(BankingException ex) {
        HttpStatus status = mapStatus(ex.getCode());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, publicMessage(ex.getCode()));
        problem.setType(
                URI.create("urn:problem:" + ex.getCode().name().toLowerCase().replace('_', '-')));
        problem.setTitle(ex.getCode().name());
        return problem;
    }

    @Override
    protected org.springframework.http.ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            org.springframework.http.HttpHeaders headers,
            org.springframework.http.HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        if (status.value() == 400)
            return new org.springframework.http.ResponseEntity<>(
                    handle(
                            new BankingException(
                                    BankingException.Code.INVALID_REQUEST, "Invalid request")),
                    headers,
                    status);
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    private String publicMessage(BankingException.Code code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            case ACCOUNT_NOT_FOUND -> "Account not found";
            case DAILY_LIMIT_EXCEEDED -> "Bank transfer limit exceeded";
            case INVALID_REQUEST, INVALID_ACCOUNT_FORMAT -> "Invalid banking request";
            case UNSUPPORTED_CURRENCY -> "Currency is not supported";
            case IDEMPOTENCY_CONFLICT -> "Idempotency key reused with different payload";
            case UPSTREAM_TIMEOUT ->
                    "Bank response timed out; retry transfers with the same idempotency key";
            default -> "Bank service unavailable";
        };
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ProblemDetail databaseUnavailable() {
        return handle(
                new BankingException(BankingException.Code.UPSTREAM_UNAVAILABLE, "Unavailable"));
    }

    private HttpStatus mapStatus(BankingException.Code code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND; // 404
            case DAILY_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS; // 429
            case INVALID_REQUEST, INVALID_ACCOUNT_FORMAT -> HttpStatus.BAD_REQUEST; // 400
            case UNSUPPORTED_CURRENCY -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE; // 503
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case UPSTREAM_AUTHENTICATION, UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY; // 502
        };
    }
}
