package com.example.omp.payment.provider;
/**
 * Payment provider integration.
 *
 * <p>Layout:
 *
 * <ul>
 *   <li>{@link com.omp.payment.provider.PaymentProvider} — vendor-neutral interface
 *   <li>{@link com.omp.payment.provider.stripe} — Stripe implementation
 *   <li>{@link com.omp.payment.provider.noop} — in-memory implementation for dev/test
 * </ul>
 *
 * <p>Selection is via {@code payment.provider} ({@code stripe} or {@code noop}). Exactly one
 * implementation is active per application context.
 */
