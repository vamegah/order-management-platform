package com.example.omp.bank.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** One database transaction owns request deduplication, both account balances and the result. */
@Service
public class BankingService {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public BankingService(
            JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(3);
    }

    public TransferResult transfer(
            String from, String to, BigDecimal amount, String currency, String reference) {
        return transfer(from, to, amount, currency, reference, "legacy:" + UUID.randomUUID());
    }

    public TransferResult transfer(
            String from,
            String to,
            BigDecimal amount,
            String currency,
            String reference,
            String requestId) {
        String hash =
                hash(
                        String.join(
                                "\n",
                                String.valueOf(from),
                                String.valueOf(to),
                                amount == null ? "null" : amount.stripTrailingZeros().toString(),
                                String.valueOf(currency),
                                String.valueOf(reference)));
        var outcome =
                transactions.execute(
                        tx -> {
                            db.update(
                                    "INSERT INTO bank_transfer(request_id,request_hash) VALUES (?,?) ON CONFLICT DO NOTHING",
                                    requestId,
                                    hash);
                            var row =
                                    db.queryForMap(
                                            "SELECT * FROM bank_transfer WHERE request_id=? FOR UPDATE",
                                            requestId);
                            if (!hash.equals(row.get("request_hash")))
                                return new Outcome(null, "IDEMPOTENCY_CONFLICT");
                            if (row.get("outcome") != null) return read(row);
                            String fault = validate(from, to, amount, currency, reference);
                            TransferResult result = null;
                            if (fault == null) {
                                var accounts =
                                        db.queryForList(
                                                "SELECT account_number,balance,currency FROM bank_account WHERE account_number IN (?,?) ORDER BY account_number FOR UPDATE",
                                                from,
                                                to);
                                if (accounts.size() != 2) fault = "ACCOUNT_NOT_FOUND";
                                else if (accounts.stream()
                                        .anyMatch(
                                                account ->
                                                        !currency.equals(account.get("currency"))))
                                    fault = "UNSUPPORTED_CURRENCY";
                                else {
                                    var source =
                                            accounts.stream()
                                                    .filter(
                                                            a ->
                                                                    from.equals(
                                                                            a.get(
                                                                                    "account_number")))
                                                    .findFirst()
                                                    .orElseThrow();
                                    BigDecimal balance = (BigDecimal) source.get("balance");
                                    if (balance.compareTo(amount) < 0) fault = "INSUFFICIENT_FUNDS";
                                    else {
                                        db.update(
                                                "UPDATE bank_account SET balance=balance-? WHERE account_number=?",
                                                amount,
                                                from);
                                        db.update(
                                                "UPDATE bank_account SET balance=balance+? WHERE account_number=?",
                                                amount,
                                                to);
                                        result =
                                                new TransferResult(
                                                        "TXN-" + UUID.randomUUID(),
                                                        "COMPLETED",
                                                        Instant.now()
                                                                .truncatedTo(
                                                                        java.time.temporal
                                                                                .ChronoUnit.MICROS),
                                                        balance.subtract(amount));
                                    }
                                }
                            }
                            if (result != null)
                                db.update(
                                        "UPDATE bank_transfer SET transaction_id=?,from_account=?,to_account=?,amount=?,currency=?,reference=?,outcome='COMPLETED',completed_at=?,new_balance=? WHERE request_id=?",
                                        result.transactionId(),
                                        from,
                                        to,
                                        amount,
                                        currency,
                                        reference,
                                        java.sql.Timestamp.from(result.timestamp()),
                                        result.newBalance(),
                                        requestId);
                            else
                                db.update(
                                        "UPDATE bank_transfer SET outcome=? WHERE request_id=?",
                                        fault,
                                        requestId);
                            return new Outcome(result, fault);
                        });
        if (outcome.fault() != null)
            throw new BankingFaultException(outcome.fault(), "Bank request declined");
        return outcome.result();
    }

    private Outcome read(java.util.Map<String, Object> row) {
        if (!"COMPLETED".equals(row.get("outcome")))
            return new Outcome(null, (String) row.get("outcome"));
        return new Outcome(
                new TransferResult(
                        (String) row.get("transaction_id"),
                        "COMPLETED",
                        instant(row.get("completed_at")),
                        (BigDecimal) row.get("new_balance")),
                null);
    }

    private Instant instant(Object value) {
        return value instanceof java.sql.Timestamp timestamp
                ? timestamp.toInstant()
                : ((java.time.OffsetDateTime) value).toInstant();
    }

    private String validate(
            String from, String to, BigDecimal amount, String currency, String reference) {
        if (from == null
                || to == null
                || !from.matches("ACC-[0-9]{1,30}")
                || !to.matches("ACC-[0-9]{1,30}")
                || from.equals(to)) return "INVALID_ACCOUNT_FORMAT";
        if (amount == null
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2
                || reference == null
                || reference.isBlank()) return "INVALID_REQUEST";
        if (amount.compareTo(new BigDecimal("10000")) > 0) return "DAILY_LIMIT_EXCEEDED";
        return "USD".equals(currency) ? null : "UNSUPPORTED_CURRENCY";
    }

    public BalanceResult getBalance(String account) {
        var rows =
                db.queryForList(
                        "SELECT balance,currency FROM bank_account WHERE account_number=?",
                        account);
        if (rows.isEmpty())
            throw new BankingFaultException("ACCOUNT_NOT_FOUND", "Account unavailable");
        return new BalanceResult(
                account,
                (BigDecimal) rows.getFirst().get("balance"),
                (String) rows.getFirst().get("currency"),
                Instant.now());
    }

    public ReverseResult reverse(String id, String reason) {
        return transactions.execute(
                tx -> {
                    var rows =
                            db.queryForList(
                                    "SELECT * FROM bank_transfer WHERE transaction_id=? FOR UPDATE",
                                    id);
                    if (rows.isEmpty())
                        throw new BankingFaultException(
                                "TRANSACTION_NOT_FOUND", "Transaction unavailable");
                    var row = rows.getFirst();
                    if (Boolean.TRUE.equals(row.get("reversed")))
                        throw new BankingFaultException(
                                "ALREADY_REVERSED", "Transaction already reversed");
                    String from = (String) row.get("from_account"),
                            to = (String) row.get("to_account");
                    var accounts =
                            db.queryForList(
                                    "SELECT account_number,currency FROM bank_account WHERE account_number IN (?,?) ORDER BY account_number FOR UPDATE",
                                    from,
                                    to);
                    if (accounts.size() != 2)
                        throw new BankingFaultException("ACCOUNT_NOT_FOUND", "Account unavailable");
                    if (accounts.stream()
                            .anyMatch(
                                    account ->
                                            !row.get("currency").equals(account.get("currency"))))
                        throw new BankingFaultException(
                                "UNSUPPORTED_CURRENCY", "Reversal currency mismatch");
                    BigDecimal amount = (BigDecimal) row.get("amount");
                    if (db.update(
                                    "UPDATE bank_account SET balance=balance-? WHERE account_number=? AND balance>=?",
                                    amount,
                                    to,
                                    amount)
                            != 1)
                        throw new BankingFaultException("INSUFFICIENT_FUNDS", "Reversal declined");
                    db.update(
                            "UPDATE bank_account SET balance=balance+? WHERE account_number=?",
                            amount,
                            from);
                    db.update("UPDATE bank_transfer SET reversed=TRUE WHERE transaction_id=?", id);
                    return new ReverseResult(id, "REVERSED", Instant.now());
                });
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Outcome(TransferResult result, String fault) {}

    public record TransferResult(
            String transactionId, String status, Instant timestamp, BigDecimal newBalance) {}

    public record BalanceResult(
            String accountNumber, BigDecimal balance, String currency, Instant timestamp) {}

    public record ReverseResult(String transactionId, String status, Instant timestamp) {}
}
