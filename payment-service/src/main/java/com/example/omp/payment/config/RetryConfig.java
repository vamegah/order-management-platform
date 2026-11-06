package com.example.omp.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry's {@code @Retryable} support.
 *
 * <p>Retry is used by:
 *
 * <ul>
 *   <li>{@link com.example.omp.payment.grpc.handler.RefundHandler} — retries on {@code
 *       OptimisticLockingFailureException} when two refunds race on the same payment.
 *   <li>{@code PaymentIntentHandler} — reserved for future retry needs.
 * </ul>
 *
 * <p>If {@code @EnableRetry} is on the main application class instead, this file can be deleted.
 * The two approaches are equivalent.
 */
@Configuration
@EnableRetry
public class RetryConfig {}
