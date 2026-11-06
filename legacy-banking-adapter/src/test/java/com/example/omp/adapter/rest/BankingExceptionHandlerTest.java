package com.example.omp.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.adapter.port.BankingException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ProblemDetail;

class BankingExceptionHandlerTest {

    private final BankingExceptionHandler handler = new BankingExceptionHandler();

    @ParameterizedTest
    @CsvSource({
        "INSUFFICIENT_FUNDS,    422",
        "ACCOUNT_NOT_FOUND,     404",
        "DAILY_LIMIT_EXCEEDED,  429",
        "INVALID_ACCOUNT_FORMAT,400",
        "UNSUPPORTED_CURRENCY,  422",
        "UPSTREAM_UNAVAILABLE,  503",
        "UPSTREAM_ERROR,        502"
    })
    void maps_code_to_status(BankingException.Code code, int expectedStatus) {
        ProblemDetail problem = handler.handle(new BankingException(code, "test"));

        assertThat(problem.getStatus()).isEqualTo(expectedStatus);
        assertThat(problem.getType().toString()).startsWith("urn:problem:");
    }
}
