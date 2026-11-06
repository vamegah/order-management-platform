package com.example.omp.bank.service;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.bank.domain.AccountRepository;
import com.example.omp.bank.domain.TransactionRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BankingServiceTest {

    private BankingService service;
    private AccountRepository accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountRepository();
        accounts.seed();
        service = new BankingService(accounts, new TransactionRepository());
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
                                        "MISSING",
                                        "ACC-2",
                                        new BigDecimal("10.00"),
                                        "USD",
                                        "REF-X"))
                .isInstanceOf(BankingFaultException.class)
                .extracting("code")
                .isEqualTo("ACCOUNT_NOT_FOUND");
    }
}
