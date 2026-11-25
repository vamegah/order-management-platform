package com.example.omp.bank.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BankFailureSimulatorTest {
    @Test
    void controlsAreDisabledByDefaultAndConsumeOnlyExplicitlyArmedCalls() {
        var db = mock(JdbcTemplate.class);
        assertThat(new BankFailureSimulator(db, false).next().mode()).isEqualTo("normal");
        verifyNoInteractions(db);
        when(db.queryForList(anyString()))
                .thenReturn(
                        java.util.List.of(java.util.Map.of("mode", "slow", "delay_ms", 1)),
                        java.util.List.of());
        var simulator = new BankFailureSimulator(db, true);
        assertThat(simulator.next().mode()).isEqualTo("slow");
        assertThat(simulator.next().mode()).isEqualTo("normal");
    }

    @Test
    void backendLatencyAndLostReplyControlsHaveDistinctBoundedFailurePoints() {
        assertThatThrownBy(() -> new BankFailureSimulator.Failure("backend", 0).before())
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("UPSTREAM_ERROR");
        new BankFailureSimulator.Failure("slow", 1).before();
        new BankFailureSimulator.Failure("lost-reply", 1).after();
        new BankFailureSimulator.Failure("normal", 0).before();
        new BankFailureSimulator.Failure("normal", 0).after();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new BankFailureSimulator.Failure("slow", 10).before())
                    .isInstanceOf(BankingFaultException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
