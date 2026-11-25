package com.example.omp.bank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"docker", "test"})
public class BankDevelopmentAccounts {
    @Bean
    org.springframework.boot.ApplicationRunner seedBankAccounts(
            org.springframework.jdbc.core.JdbcTemplate db) {
        return args -> {
            for (var entry :
                    java.util.Map.of("ACC-1", "10000.00", "ACC-2", "5000.00", "ACC-3", "25000.00")
                            .entrySet())
                db.update(
                        "INSERT INTO bank_account(account_number,currency,balance) VALUES (?,'USD',?) ON CONFLICT DO NOTHING",
                        entry.getKey(),
                        new java.math.BigDecimal(entry.getValue()));
        };
    }
}
