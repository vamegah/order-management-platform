package com.example.omp.adapter.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.port.BankingException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IdempotencyStoreTest {
    JdbcTemplate db;
    IdempotencyStore store;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + java.util.UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        db = new JdbcTemplate(ds);
        org.springframework.jdbc.datasource.init.DatabasePopulatorUtils.execute(
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V1__durable_transfer_requests.sql"),
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V2__provider_transaction_identity.sql")),
                ds);
        store = new IdempotencyStore(db, new DataSourceTransactionManager(ds));
    }

    TransferResponse response() {
        return new TransferResponse(
                "txn", "COMPLETED", Instant.parse("2026-11-24T12:00:00Z"), new BigDecimal("10.00"));
    }

    @Test
    void completedRecordSurvivesNewStoreAndNormalizesResponse() {
        var expected = store.execute("key", "hash", id -> response());
        var restart =
                new IdempotencyStore(db, new DataSourceTransactionManager(db.getDataSource()));
        assertThat(
                        restart.execute(
                                "key",
                                "hash",
                                id -> {
                                    throw new AssertionError("Duplicate upstream call");
                                }))
                .isEqualTo(expected);
        assertThatThrownBy(() -> restart.execute("key", "other", id -> response()))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void transportFailuresKeepStableBankIdentityAndBusinessDeclinesAreDurable() {
        assertThatThrownBy(
                        () ->
                                store.execute(
                                        "pending",
                                        "hash",
                                        id -> {
                                            assertThat(id).isEqualTo("pending");
                                            throw new BankingException(
                                                    BankingException.Code.UPSTREAM_TIMEOUT,
                                                    "timeout");
                                        }))
                .isInstanceOf(BankingException.class);
        assertThat(
                        store.execute(
                                "pending",
                                "hash",
                                id -> {
                                    assertThat(id).isEqualTo("pending");
                                    return response();
                                }))
                .isEqualTo(response());
        assertThatThrownBy(
                        () ->
                                store.execute(
                                        "declined",
                                        "hash",
                                        id -> {
                                            throw new BankingException(
                                                    BankingException.Code.INSUFFICIENT_FUNDS,
                                                    "private");
                                        }))
                .isInstanceOf(BankingException.class);
        assertThatThrownBy(
                        () ->
                                store.execute(
                                        "declined",
                                        "hash",
                                        id -> {
                                            throw new AssertionError();
                                        }))
                .isInstanceOf(BankingException.class)
                .hasMessageNotContaining("private");
    }

    @Test
    void unkeyedRequestsUseIndependentBankIdentities() {
        var keys = new java.util.HashSet<String>();
        for (int i = 0; i < 2; i++)
            store.execute(
                    null,
                    "hash",
                    id -> {
                        keys.add(id);
                        return response();
                    });
        assertThat(keys).hasSize(2);
    }

    @org.junit.jupiter.api.Test
    void providerIdsRemainOpaqueAcrossDurableRetries() {
        var expected =
                new TransferResponse(
                        "provider-" + "x".repeat(256),
                        "COMPLETED",
                        Instant.parse("2026-11-24T12:00:00Z"),
                        new BigDecimal("10.00"));
        assertThat(store.execute("long-provider-id", "hash", id -> expected)).isEqualTo(expected);
        assertThat(
                        store.execute(
                                "long-provider-id",
                                "hash",
                                id -> {
                                    throw new AssertionError("Repeated upstream call");
                                }))
                .isEqualTo(expected);
    }
}
