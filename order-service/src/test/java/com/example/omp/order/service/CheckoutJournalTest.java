package com.example.omp.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.error.InvalidOrderStateException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CheckoutJournalTest {
    @Test
    void fencedAcceptanceUsesTheOrderConflictContract() {
        var journal = new CheckoutJournal(mock(JdbcTemplate.class));
        assertThatThrownBy(() -> journal.accept(UUID.randomUUID()))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessage("Checkout is fenced by recovery");
    }
}
