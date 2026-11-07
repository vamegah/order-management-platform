package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.dto.RecoveryAdoptionRequest;
import com.example.omp.order.dto.RecoveryAdoptionResult;
import com.example.omp.order.error.InvalidOrderDataException;
import com.example.omp.order.error.InvalidOrderStateException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

class CheckoutRecoveryAdoptionTest {
    private final UUID order = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CheckoutRecoveryAdoption adoption = new CheckoutRecoveryAdoption(jdbc);
    private final RecoveryAdoptionRequest request =
            new RecoveryAdoptionRequest("  Reviewed orphan  ");

    @Test
    void aKnownOrderCannotBeSubmittedForOrphanCompensation() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(order))).thenReturn(true);
        assertThatThrownBy(() -> adoption.adopt(order, request, "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTED", "FAILED", "RECOVERING", "RECOVERED", "ACCEPTED"})
    void normalAttemptsAreProtectedRegardlessOfRecoveryStatus(String status) {
        state(0, new RecoveryAdoptionResult(order, status, false, null, null, null));
        assertThatThrownBy(() -> adoption.adopt(order, request, "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECOVERING", "RECOVERED"})
    void aRepeatAdmissionRetainsTheFirstOperatorsAuditAndRecoveryProgress(String status) {
        var current =
                new RecoveryAdoptionResult(
                        order,
                        status,
                        false,
                        "first-operator",
                        "Original review",
                        Instant.parse("2026-11-07T00:00:00Z"));
        state(0, current);
        assertThat(adoption.adopt(order, request, "second-operator")).isEqualTo(current);
    }

    @Test
    void anAcceptedAttemptIsNeverAnOrphanEvenIfAnAuditRowWasPresent() {
        state(
                0,
                new RecoveryAdoptionResult(
                        order, "ACCEPTED", false, "operator", "Old review", Instant.now()));
        assertThatThrownBy(() -> adoption.adopt(order, request, "operator"))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    void aNewAdmissionReturnsItsDurableAuditIdentityAndNormalizedReason() {
        state(1, new RecoveryAdoptionResult(order, "FAILED", false, null, null, null));
        var at = Instant.parse("2026-11-07T00:00:00Z");
        when(jdbc.queryForObject(
                        anyString(),
                        eq(java.sql.Timestamp.class),
                        eq(order),
                        eq("operator"),
                        eq("Reviewed orphan")))
                .thenReturn(java.sql.Timestamp.from(at));
        assertThat(adoption.adopt(order, request, "operator"))
                .isEqualTo(
                        new RecoveryAdoptionResult(
                                order, "FAILED", true, "operator", "Reviewed orphan", at));
    }

    private void state(int inserted, RecoveryAdoptionResult current) {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(order))).thenReturn(false);
        when(jdbc.update(anyString(), eq(order))).thenReturn(inserted);
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<RecoveryAdoptionResult>>any(),
                        eq(order)))
                .thenReturn(List.of(current));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null-actor",
                "blank-actor",
                "long-actor",
                "null-order",
                "null-request",
                "null-reason",
                "blank-reason",
                "long-reason"
            })
    void unauditableOrInvalidSubmissionsCannotReachTheDatabase(String fault) {
        var jdbc = mock(JdbcTemplate.class);
        var adoption = new CheckoutRecoveryAdoption(jdbc);
        String actor =
                switch (fault) {
                    case "null-actor" -> null;
                    case "blank-actor" -> " ";
                    case "long-actor" -> "x".repeat(201);
                    default -> "operator-subject";
                };
        UUID order = fault.equals("null-order") ? null : UUID.randomUUID();
        String reason =
                switch (fault) {
                    case "null-reason" -> null;
                    case "blank-reason" -> " ";
                    case "long-reason" -> "x".repeat(501);
                    default -> "Verified historical orphan";
                };
        var request = fault.equals("null-request") ? null : new RecoveryAdoptionRequest(reason);
        assertThatThrownBy(() -> adoption.adopt(order, request, actor))
                .isInstanceOf(
                        fault.endsWith("actor")
                                ? AccessDeniedException.class
                                : InvalidOrderDataException.class);
        verifyNoInteractions(jdbc);
    }
}
