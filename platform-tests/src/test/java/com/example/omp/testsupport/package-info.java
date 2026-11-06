/**
 * Shared test infrastructure for Phase 5 end-to-end tests.
 *
 * <p><strong>Structure:</strong>
 *
 * <ul>
 *   <li>{@link com.omp.tests.outbox} — outbox relay verification (Batch 9.1)
 *   <li>{@link com.omp.tests.inbound} — inbound webhook verification (Batch 9.2)
 *   <li>{@link com.omp.tests.outbound} — outbound webhook verification (Batch 9.3)
 *   <li>{@link com.omp.tests.system} — cross-service full-system tests (Batch 9.4 + 9.5)
 * </ul>
 *
 * <p><strong>Conventions:</strong>
 *
 * <ul>
 *   <li>All classes end in {@code IT} and run under Failsafe, not Surefire.
 *   <li>Containers are shared via {@link com.omp.testsupport.SharedContainers}.
 *   <li>Fast retry is enabled with the {@code test-fast-retry} profile.
 *   <li>Compose-driven tests are tagged {@code compose} and excluded from the fast CI job.
 * </ul>
 */
package com.example.omp.testsupport;
