package com.example.omp.bank.service;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@org.springframework.test.context.ActiveProfiles("test")
class BankingServiceTest {

    @org.springframework.beans.factory.annotation.Autowired private BankingService service;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate db;

    @BeforeEach
    void setUp() {
        db.update("DELETE FROM bank_transfer");
        db.update("DELETE FROM bank_account");
        db.update(
                "INSERT INTO bank_account VALUES ('ACC-1','USD',10000),('ACC-2','USD',5000),('ACC-3','USD',25000)");
    }

    @Test
    void transfer_succeeds_when_funds_available() {
        var result = service.transfer("ACC-1", "ACC-2", new BigDecimal("100.00"), "USD", "REF-1");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.newBalance()).isEqualByComparingTo("9900.00");
    }

    @Test
    void transfer_fails_when_insufficient_funds() {
        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        "ACC-2",
                                        "ACC-1",
                                        new BigDecimal("6000.00"),
                                        "USD",
                                        "REF-X"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void transfer_fails_when_amount_over_daily_limit() {
        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        "ACC-1",
                                        "ACC-2",
                                        new BigDecimal("10001.00"),
                                        "USD",
                                        "REF-X"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void transfer_fails_when_account_not_found() {
        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        "ACC-404",
                                        "ACC-2",
                                        new BigDecimal("10.00"),
                                        "USD",
                                        "REF-X"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void duplicateTransferSurvivesServiceRecreationAndDetectsPayloadConflict() {
        var result =
                service.transfer(
                        "ACC-1", "ACC-2", new BigDecimal("12.34"), "USD", "ref", "durable-key");
        var restarted =
                new BankingService(
                        db,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                db.getDataSource()));
        assertThat(
                        restarted.transfer(
                                "ACC-1",
                                "ACC-2",
                                new BigDecimal("12.340"),
                                "USD",
                                "ref",
                                "durable-key"))
                .isEqualTo(result);
        assertThatThrownBy(
                        () ->
                                restarted.transfer(
                                        "ACC-1",
                                        "ACC-2",
                                        BigDecimal.ONE,
                                        "USD",
                                        "ref",
                                        "durable-key"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(service.getBalance("ACC-1").balance()).isEqualByComparingTo("9987.66");
    }

    @Test
    void declinedTransferRemainsDeclinedAfterFundsChange() {
        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        "ACC-2",
                                        "ACC-1",
                                        new BigDecimal("6000"),
                                        "USD",
                                        "ref",
                                        "declined"))
                .isInstanceOf(BankingFaultException.class);
        db.update("UPDATE bank_account SET balance=10000 WHERE account_number='ACC-2'");
        assertThatThrownBy(
                        () ->
                                service.transfer(
                                        "ACC-2",
                                        "ACC-1",
                                        new BigDecimal("6000"),
                                        "USD",
                                        "ref",
                                        "declined"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(service.getBalance("ACC-2").balance()).isEqualByComparingTo("10000");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "ACC-1,ACC-1,1,USD,INVALID_ACCOUNT_FORMAT",
        "bad,ACC-2,1,USD,INVALID_ACCOUNT_FORMAT",
        "ACC-1,ACC-2,0,USD,INVALID_REQUEST",
        "ACC-1,ACC-2,-1,USD,INVALID_REQUEST",
        "ACC-1,ACC-2,0.001,USD,INVALID_REQUEST",
        "ACC-1,ACC-2,1,EUR,UNSUPPORTED_CURRENCY",
        "ACC-1,ACC-999,1,USD,ACCOUNT_NOT_FOUND"
    })
    void rejectsInvalidMoneyMovementWithoutChangingBalances(
            String from, String to, BigDecimal amount, String currency, String code) {
        assertThatThrownBy(() -> service.transfer(from, to, amount, currency, "ref", "invalid"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo(code);
        assertThat(service.getBalance("ACC-1").balance()).isEqualByComparingTo("10000");
        assertThat(db.queryForObject("SELECT SUM(balance) FROM bank_account", BigDecimal.class))
                .isEqualByComparingTo("40000");
    }

    @Test
    void concurrentDuplicatesMoveMoneyExactlyOnce() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var jobs =
                    new java.util.ArrayList<
                            java.util.concurrent.Future<BankingService.TransferResult>>();
            for (int n = 0; n < 12; n++)
                jobs.add(
                        executor.submit(
                                () ->
                                        service.transfer(
                                                "ACC-1",
                                                "ACC-2",
                                                new BigDecimal("12.34"),
                                                "USD",
                                                "ref",
                                                "concurrent")));
            var first = jobs.getFirst().get();
            for (var job : jobs) assertThat(job.get()).isEqualTo(first);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM bank_transfer WHERE outcome='COMPLETED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(service.getBalance("ACC-1").balance()).isEqualByComparingTo("9987.66");
        assertThat(service.getBalance("ACC-2").balance()).isEqualByComparingTo("5012.34");
    }

    @Test
    void reversalIsAtomicAndCannotRepeatOrOverdraw() {
        var paid = service.transfer("ACC-1", "ACC-2", BigDecimal.TEN, "USD", "ref");
        db.update("UPDATE bank_account SET balance=0 WHERE account_number='ACC-2'");
        assertThatThrownBy(() -> service.reverse(paid.transactionId(), "refund"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(service.getBalance("ACC-1").balance()).isEqualByComparingTo("9990");
        db.update("UPDATE bank_account SET balance=5010 WHERE account_number='ACC-2'");
        assertThat(service.reverse(paid.transactionId(), "refund").status()).isEqualTo("REVERSED");
        assertThatThrownBy(() -> service.reverse(paid.transactionId(), "refund"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("ALREADY_REVERSED");
        assertThatThrownBy(() -> service.reverse("missing", "refund"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("TRANSACTION_NOT_FOUND");
        assertThatThrownBy(() -> service.getBalance("ACC-999"))
                .isInstanceOf(BankingFaultException.class);
    }

    @Test
    void transferAndReversalNeverMoveDifferentAccountCurrencies() {
        var paid = service.transfer("ACC-1", "ACC-2", BigDecimal.ONE, "USD", "currency-guard");
        db.update("UPDATE bank_account SET currency='EUR' WHERE account_number='ACC-2'");
        assertThatThrownBy(() -> service.transfer("ACC-1", "ACC-2", BigDecimal.ONE, "USD", "ref"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_CURRENCY");
        assertThatThrownBy(() -> service.reverse(paid.transactionId(), "refund"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(service.getBalance("ACC-1").balance()).isEqualByComparingTo("9999");
        assertThat(service.getBalance("ACC-2").balance()).isEqualByComparingTo("5001");
    }
}
