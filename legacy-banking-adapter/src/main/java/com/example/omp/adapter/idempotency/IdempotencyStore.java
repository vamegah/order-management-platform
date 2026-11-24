package com.example.omp.adapter.idempotency;

import com.example.omp.adapter.dto.TransferResponse;
import com.example.omp.adapter.port.BankingException;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Records survive replicas/restarts; pending outcomes always reuse the original bank identity. */
@Component
public class IdempotencyStore {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public IdempotencyStore(
            JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager) {
        this.db = db;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(3);
    }

    public TransferResponse execute(
            String key, String hash, Function<String, TransferResponse> operation) {
        String id = key == null ? "unkeyed:" + UUID.randomUUID() : key;
        var row =
                transactions.execute(
                        tx -> {
                            db.update(
                                    "INSERT INTO adapter_transfer(request_id,request_hash) VALUES (?,?) ON CONFLICT DO NOTHING",
                                    id,
                                    hash);
                            var value =
                                    db.queryForMap(
                                            "SELECT * FROM adapter_transfer WHERE request_id=? FOR UPDATE",
                                            id);
                            if (!hash.equals(value.get("request_hash")))
                                throw new IdempotencyConflictException("Idempotency conflict");
                            return value;
                        });
        if (row.get("outcome") != null) {
            if (!"COMPLETED".equals(row.get("outcome")))
                throw new BankingException(
                        BankingException.Code.valueOf((String) row.get("outcome")),
                        "Bank request declined");
            return new TransferResponse(
                    (String) row.get("transaction_id"),
                    (String) row.get("status"),
                    instant(row.get("completed_at")),
                    (java.math.BigDecimal) row.get("new_balance"));
        }
        try {
            var original = operation.apply(id);
            var response =
                    new TransferResponse(
                            original.transactionId(),
                            original.status(),
                            original.timestamp().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                            original.newBalance().setScale(2, java.math.RoundingMode.UNNECESSARY));
            db.update(
                    "UPDATE adapter_transfer SET transaction_id=?,status=?,completed_at=?,new_balance=?,outcome='COMPLETED' WHERE request_id=?",
                    response.transactionId(),
                    response.status(),
                    java.sql.Timestamp.from(response.timestamp()),
                    response.newBalance(),
                    id);
            return response;
        } catch (BankingException ex) {
            if (ex.getCode().business())
                db.update(
                        "UPDATE adapter_transfer SET outcome=? WHERE request_id=? AND outcome IS NULL",
                        ex.getCode().name(),
                        id);
            throw ex;
        }
    }

    private java.time.Instant instant(Object value) {
        return value instanceof java.sql.Timestamp timestamp
                ? timestamp.toInstant()
                : ((java.time.OffsetDateTime) value).toInstant();
    }
}
