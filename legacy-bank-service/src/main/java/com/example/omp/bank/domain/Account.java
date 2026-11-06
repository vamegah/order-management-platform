package com.example.omp.bank.domain;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

public class Account {

    private final String accountNumber;
    private final String currency;
    private final AtomicReference<BigDecimal> balance;

    public Account(String accountNumber, String currency, BigDecimal initialBalance) {
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.balance = new AtomicReference<>(initialBalance);
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance.get();
    }

    /** Returns the new balance, or throws if insufficient funds. */
    public BigDecimal debit(BigDecimal amount) {
        return balance.updateAndGet(
                current -> {
                    if (current.compareTo(amount) < 0) {
                        throw new InsufficientFundsException(
                                "Insufficient funds: balance=" + current + ", required=" + amount);
                    }
                    return current.subtract(amount);
                });
    }

    public BigDecimal credit(BigDecimal amount) {
        return balance.updateAndGet(current -> current.add(amount));
    }
}
