package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.service.PaymentCreationLock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentCreationLockTest {
    @Test
    void rejectsUnsafeKeysBeforeDatabaseAccess() {
        var jdbc = mock(JdbcTemplate.class);
        var lock = new PaymentCreationLock(jdbc);
        lock.acquire(null);
        assertThatThrownBy(() -> lock.acquire("x".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lock.acquire("key\nline"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
