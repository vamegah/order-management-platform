package com.example.omp.shared.events.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link OutboxEvent}.
 *
 * <p>The drainer uses {@link #claimPendingBatch(int)} to atomically claim a batch of PENDING rows
 * using Postgres's {@code SELECT ... FOR UPDATE SKIP LOCKED}. This is the standard
 * concurrent-worker pattern: active drainers claim disjoint rows. A crash after acknowledgment can
 * still cause at-least-once redelivery.
 *
 * <p>The cleanup job uses {@link #deletePublishedOlderThan(Instant)} to remove published rows past
 * their retention window.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims up to {@code limit} PENDING rows, oldest first, locking them for the duration of the
     * calling transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} means: lock the rows we return, but skip any rows already
     * locked by another drainer. Concurrent drainers therefore get disjoint sets.
     *
     * <p><b>Transaction requirement:</b> must be called within a transaction. The locks are held
     * until the transaction commits or rolls back.
     */
    @Query(
            value =
                    """
                    SELECT e.* FROM outbox_event e
                    WHERE e.status = 'PENDING'
                      AND NOT EXISTS (
                        SELECT 1 FROM outbox_event prior
                        WHERE prior.topic=e.topic AND prior.aggregate_id=e.aggregate_id
                          AND prior.sequence_id < e.sequence_id AND prior.status IN ('PENDING','FAILED')
                      )
                    ORDER BY e.sequence_id
                    LIMIT :limit
                    FOR UPDATE OF e SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("limit") int limit);

    /**
     * Counts PENDING rows. Used by the metrics gauge. Counts PENDING + FAILED, since both indicate
     * work that hasn't completed successfully.
     */
    @Query("SELECT COUNT(o) FROM OutboxEvent o WHERE o.status IN ('PENDING', 'FAILED')")
    long countUnpublished();

    /** Counts FAILED rows specifically. Used to alert on operator intervention. */
    @Query("SELECT COUNT(o) FROM OutboxEvent o WHERE o.status = 'FAILED'")
    long countFailed();

    /**
     * Deletes PUBLISHED rows older than the retention window. Called by the scheduled cleanup job.
     *
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.status = 'PUBLISHED' AND o.publishedAt < :cutoff")
    int deletePublishedOlderThan(@Param("cutoff") Instant cutoff);

    @Query("SELECT MIN(o.createdAt) FROM OutboxEvent o WHERE o.status IN ('PENDING', 'FAILED')")
    java.time.Instant oldestUnpublished();
}
