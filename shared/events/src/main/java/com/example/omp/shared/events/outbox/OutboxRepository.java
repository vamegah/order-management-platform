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
 * concurrent-worker pattern: two drainers running simultaneously will claim disjoint batches, no
 * double-publish.
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
                    SELECT * FROM outbox_event
                    WHERE status = 'PENDING'
                    ORDER BY created_at ASC
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
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
}
