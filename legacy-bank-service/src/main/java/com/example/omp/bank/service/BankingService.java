package com.example.omp.bank.service;

import com.example.omp.bank.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class BankingService {

    /** Daily per-transaction limit — part of the "legacy" behavior we're simulating. */
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("10000.00");

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public BankingService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public TransferResult transfer(
            String from, String to, BigDecimal amount, String currency, String reference) {
        if (amount.compareTo(DAILY_LIMIT) > 0) {
            throw new BankingFaultException(
                    "DAILY_LIMIT_EXCEEDED", "Amount exceeds daily limit of " + DAILY_LIMIT);
        }
        if (!currency.equals("USD")) {
            throw new BankingFaultException("UNSUPPORTED_CURRENCY", "Only USD is supported");
        }

        Account fromAccount =
                accounts.find(from)
                        .orElseThrow(
                                () ->
                                        new BankingFaultException(
                                                "ACCOUNT_NOT_FOUND",
                                                "Source account not found: " + from));
        Account toAccount =
                accounts.find(to)
                        .orElseThrow(
                                () ->
                                        new BankingFaultException(
                                                "ACCOUNT_NOT_FOUND",
                                                "Destination account not found: " + to));

        BigDecimal newFromBalance;
        try {
            newFromBalance = fromAccount.debit(amount);
        } catch (InsufficientFundsException e) {
            throw new BankingFaultException("INSUFFICIENT_FUNDS", e.getMessage());
        }

        toAccount.credit(amount);

        Transaction txn =
                new Transaction(
                        transactions.nextId(),
                        from,
                        to,
                        amount,
                        currency,
                        reference,
                        Instant.now(),
                        Transaction.TransactionStatus.COMPLETED);
        transactions.save(txn);

        return new TransferResult(txn.id(), "COMPLETED", Instant.now(), newFromBalance);
    }

    public BalanceResult getBalance(String accountNumber) {
        Account account =
                accounts.find(accountNumber)
                        .orElseThrow(
                                () ->
                                        new BankingFaultException(
                                                "ACCOUNT_NOT_FOUND",
                                                "Account not found: " + accountNumber));
        return new BalanceResult(
                account.getAccountNumber(),
                account.getBalance(),
                account.getCurrency(),
                Instant.now());
    }

    public ReverseResult reverse(String transactionId, String reason) {
        Transaction txn =
                transactions
                        .find(transactionId)
                        .orElseThrow(
                                () ->
                                        new BankingFaultException(
                                                "TRANSACTION_NOT_FOUND",
                                                "Transaction not found: " + transactionId));

        if (txn.status() == Transaction.TransactionStatus.REVERSED) {
            throw new BankingFaultException(
                    "ALREADY_REVERSED", "Transaction already reversed: " + transactionId);
        }

        // Reverse the money movement
        Account from = accounts.require(txn.fromAccount());
        Account to = accounts.require(txn.toAccount());
        to.debit(txn.amount());
        from.credit(txn.amount());

        transactions.markReversed(transactionId);

        return new ReverseResult(transactionId, "REVERSED", Instant.now());
    }

    // ---------- result records ----------

    public record TransferResult(
            String transactionId, String status, Instant timestamp, BigDecimal newBalance) {}

    public record BalanceResult(
            String accountNumber, BigDecimal balance, String currency, Instant timestamp) {}

    public record ReverseResult(String transactionId, String status, Instant timestamp) {}
}
