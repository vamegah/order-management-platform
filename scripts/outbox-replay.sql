-- Run with psql -X --set=ON_ERROR_STOP=1 --set=id=<uuid> --set=reason=<reason> -f scripts/outbox-replay.sql
-- Set PGSERVICE/PG* using the owning service's schema and an authenticated operator identity.
BEGIN;
WITH retried AS (
    UPDATE outbox_event SET status='PENDING',attempts=0,last_error=NULL
    WHERE id=:'id'::uuid AND status='FAILED' RETURNING id
), audited AS (
    INSERT INTO outbox_recovery_audit(outbox_id,reason)
    SELECT id,:'reason' FROM retried RETURNING id
)
SELECT count(*) AS changed FROM audited \gset
\if :changed
COMMIT;
\else
ROLLBACK;
\echo 'The record is missing or is not FAILED; nothing was replayed.'
\quit 3
\endif
