package com.example.omp.bank.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();

    public Transaction save(Transaction txn) {
        transactions.put(txn.id(), txn);
        return txn;
    }

    public Optional<Transaction> find(String id) {
        return Optional.ofNullable(transactions.get(id));
    }

    public Transaction markReversed(String id) {
        Transaction existing = transactions.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Transaction not found: " + id);
        }
        Transaction reversed =
                new Transaction(
                        existing.id(),
                        existing.fromAccount(),
                        existing.toAccount(),
                        existing.amount(),
                        existing.currency(),
                        existing.reference(),
                        existing.timestamp(),
                        Transaction.TransactionStatus.REVERSED);
        transactions.put(id, reversed);
        return reversed;
    }

    public String nextId() {
        return "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
