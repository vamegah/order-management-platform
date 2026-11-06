package com.example.omp.adapter.rest;

import com.example.omp.adapter.port.BankingException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class BankingExceptionHandler {

    @ExceptionHandler(com.example.omp.adapter.idempotency.IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict() {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT, "Idempotency key reused with different payload");
        problem.setType(URI.create("urn:problem:idempotency-conflict"));
        problem.setTitle("IDEMPOTENCY_CONFLICT");
        return problem;
    }

    @ExceptionHandler(BankingException.class)
    public ProblemDetail handle(BankingException ex) {
        HttpStatus status = mapStatus(ex.getCode());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setType(
                URI.create("urn:problem:" + ex.getCode().name().toLowerCase().replace('_', '-')));
        problem.setTitle(ex.getCode().name());
        return problem;
    }

    private HttpStatus mapStatus(BankingException.Code code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND; // 404
            case DAILY_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS; // 429
            case INVALID_ACCOUNT_FORMAT -> HttpStatus.BAD_REQUEST; // 400
            case UNSUPPORTED_CURRENCY -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            case UPSTREAM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE; // 503
            case UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY; // 502
        };
    }
}
