package com.example.omp.payment.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class BankingAdapterClient {

    private static final Logger log = LoggerFactory.getLogger(BankingAdapterClient.class);

    private final WebClient webClient;

    public BankingAdapterClient(@Value("${banking.adapter.url}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "bankingAdapter", fallbackMethod = "transferFallback")
    @Retry(name = "bankingAdapter")
    public TransferResult transfer(TransferCommand command) {
        String idempotencyKey =
                command.idempotencyKey() != null
                        ? command.idempotencyKey()
                        : UUID.randomUUID().toString();

        return webClient
                .post()
                .uri("/api/v1/banking/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .bodyValue(command)
                .retrieve()
                .bodyToMono(TransferResult.class)
                .timeout(Duration.ofSeconds(6))
                .block();
    }

    public TransferResult transferFallback(TransferCommand cmd, Throwable t) {
        log.warn("Banking adapter unavailable, queuing transfer: {} ", cmd.reference(), t);
        throw new BankingUnavailableException(
                "Banking temporarily unavailable; transfer queued for retry", t);
    }

    // ---------- DTOs (matching adapter's REST contract) ----------

    public record TransferCommand(
            String fromAccount,
            String toAccount,
            BigDecimal amount,
            String currency,
            String reference,
            String idempotencyKey) {}

    public record TransferResult(
            String transactionId,
            String status,
            java.time.Instant timestamp,
            BigDecimal newBalance) {}
}
