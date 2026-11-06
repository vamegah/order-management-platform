package com.example.omp.bank.domain;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    @PostConstruct
    public void seed() {
        // Seed accounts for local testing
        accounts.put("ACC-1", new Account("ACC-1", "USD", new BigDecimal("10000.00")));
        accounts.put("ACC-2", new Account("ACC-2", "USD", new BigDecimal("5000.00")));
        accounts.put("ACC-3", new Account("ACC-3", "USD", new BigDecimal("25000.00")));
    }

    public Optional<Account> find(String accountNumber) {
        return Optional.ofNullable(accounts.get(accountNumber));
    }

    public Account require(String accountNumber) {
        return find(accountNumber)
                .orElseThrow(
                        () -> new IllegalArgumentException("Account not found: " + accountNumber));
    }
}
