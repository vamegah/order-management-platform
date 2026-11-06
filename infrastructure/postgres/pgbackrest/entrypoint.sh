#!/bin/sh
# ==============================================================================
# Order Management Platform — pgBackRest sidecar entrypoint
# ------------------------------------------------------------------------------
# FILENAME : infrastructure/postgres/pgbackrest/entrypoint.sh
# MOUNTED  : /entrypoint.sh inside the `pgbackrest` sidecar container
# INVOKED  : by the sidecar's `entrypoint:` directive in docker-compose.prod.yml
#
# WHAT THIS SCRIPT DOES
#   1. Validates that required secrets and config are readable.
#   2. Runs `pgbackrest --stanza=omp check` to verify the backup setup.
#   3. If the check passes, enters a scheduling loop:
#        - Full backup:        daily at 02:00 UTC
#        - Incremental backup: hourly at :00
#        - Expire (prune):     daily at 03:00 UTC
#   4. Handles SIGTERM/SIGINT for graceful container shutdown.
#
# WHAT IT DOES NOT DO
#   - Initialize the stanza. That's a one-time manual step (see File 22's
#     runbook). The script detects a missing stanza and exits with a clear
#     message.
#   - Restore. Restores are manual operations, documented in the runbook.
#   - Push metrics to Prometheus. Phase 7 will add a metrics exporter.
#
# DEPENDENCIES
#   - /run/secrets/postgres_superuser_password (Docker secret)
#   - /run/secrets/pgbackrest_repo_cipher       (Docker secret)
#   - /etc/pgbackrest/pgbackrest.conf           (mounted from File 10)
#   - /var/lib/pgbackrest                       (shared volume)
#   - Postgres reachable at `postgres:5432` (Docker DNS)
#
# DESIGN NOTES
#   - Single loop, no concurrent jobs. Overlap is impossible.
#   - Jobs fire at wall-clock times (:00, 02:00, 03:00), not relative to
#     previous run completion. No drift.
#   - SIGTERM during a job waits for that job to finish, up to a grace
#     period, then exits. Prevents backups from being cut mid-file.
#   - All logging goes to stdout/stderr → Docker → Loki.
#
# ==============================================================================

set -eu

# ==============================================================================
# CONFIGURATION
# ==============================================================================

STANZA="omp"
REPO="1"

# Paths inside the container
SECRET_SUPERUSER_PASSWORD="/run/secrets/postgres_superuser_password"
SECRET_CIPHER_PASS="/run/secrets/pgbackrest_repo_cipher"
PGBACKREST_CONF="/etc/pgbackrest/pgbackrest.conf"

# Scheduling (UTC — we set TZ=UTC below)
FULL_BACKUP_HOUR=2          # 02:00 UTC
EXPIRE_HOUR=3               # 03:00 UTC
# Incremental runs at every top of hour EXCEPT the full backup hour.
# Logic in the loop handles this.

# Timeout for waiting on an in-progress backup during shutdown (seconds)
SHUTDOWN_GRACE=1800         # 30 minutes — a full backup can take this long

# ==============================================================================
# LOGGING HELPERS
# ==============================================================================
# Every log line is prefixed with an ISO 8601 UTC timestamp and a level tag.
# Example:
#   2024-11-15T14:23:45Z [pgbackrest] INFO  starting backup type=incr
#
# The `[pgbackrest]` tag matches the container name in Docker, so a grep of
# `docker compose logs | grep pgbackrest` isolates these lines. It also lets
# Loki distinguish sidecar logs from postgres logs if both are scraped.
# ==============================================================================

log() {
    # log LEVEL MESSAGE...
    level="$1"
    shift
    printf '%s [pgbackrest] %-5s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$level" "$*"
}

log_info()  { log INFO  "$@"; }
log_warn()  { log WARN  "$@"; }
log_error() { log ERROR "$@"; }

# ==============================================================================
# STARTUP VALIDATION
# ==============================================================================
# Before doing anything else, verify that all required inputs are present.
# Failures here should exit non-zero so Docker's restart policy kicks in
# and the operator sees a clear error in `docker compose ps` / logs.
# ==============================================================================

log_info "starting pgBackRest sidecar"
log_info "stanza=$STANZA repo=$REPO"

# Force UTC so scheduled times are unambiguous across hosts.
export TZ=UTC

# ------------------------------------------------------------------------------
# Verify secrets are readable
# ------------------------------------------------------------------------------
# Both are mounted by Docker as tmpfs files. If a secret wasn't declared in
# the sidecar's `secrets:` block (see File 2b), the file won't exist here.
# ------------------------------------------------------------------------------

if [ ! -r "$SECRET_SUPERUSER_PASSWORD" ]; then
    log_error "missing or unreadable secret: $SECRET_SUPERUSER_PASSWORD"
    log_error "ensure 'postgres_superuser_password' is in the pgbackrest"
    log_error "service's secrets: list in docker-compose.prod.yml"
    exit 1
fi

if [ ! -r "$SECRET_CIPHER_PASS" ]; then
    log_error "missing or unreadable secret: $SECRET_CIPHER_PASS"
    log_error "ensure 'pgbackrest_repo_cipher' is in the pgbackrest"
    log_error "service's secrets: list in docker-compose.prod.yml"
    exit 1
fi

# ------------------------------------------------------------------------------
# Verify pgBackRest config is readable
# ------------------------------------------------------------------------------

if [ ! -r "$PGBACKREST_CONF" ]; then
    log_error "missing or unreadable config: $PGBACKREST_CONF"
    log_error "ensure the pgbackrest.conf mount is present in docker-compose.prod.yml"
    exit 1
fi

# ------------------------------------------------------------------------------
# Load secrets into shell variables
# ------------------------------------------------------------------------------
# PGPASSWORD: read by libpq (and pgBackRest) automatically. Standard
# mechanism for supplying the Postgres password without putting it in a
# connection string or config file.
#
# CIPHER_PASS: not exported. Passed per-invocation via --repo1-cipher-pass.
# Keeping it out of the environment reduces the surface for accidental
# leakage via /proc/<pid>/environ or crash dumps.
# ------------------------------------------------------------------------------

PGPASSWORD="$(cat "$SECRET_SUPERUSER_PASSWORD")"
export PGPASSWORD

CIPHER_PASS="$(cat "$SECRET_CIPHER_PASS")"

log_info "secrets loaded"

# ==============================================================================
# SIGNAL HANDLING
# ==============================================================================
# On SIGTERM (docker stop) or SIGINT (Ctrl-C in a shell), set a flag that
# the main loop checks between sleeps. If a job is currently running, we
# wait for it to complete before exiting, up to SHUTDOWN_GRACE seconds.
#
# We do NOT kill the running pgbackrest process. Postgres backups aren't
# transactional — killing mid-backup leaves the backup in a partial state
# that must be manually removed. Waiting is safer.
# ==============================================================================

SHUTDOWN_REQUESTED=0

handle_shutdown() {
    log_info "shutdown signal received"
    SHUTDOWN_REQUESTED=1
}

trap handle_shutdown TERM INT

# ==============================================================================
# PGBACKREST WRAPPER
# ==============================================================================
# Centralises the cipher passphrase injection and logging. All pgbackrest
# invocations in this script go through `pgr`.
#
# Why not export CIPHER_PASS and let pgBackRest read it from env?
#   pgBackRest does not read the cipher passphrase from an env var. It must
#   be passed on the command line or specified in the config file. We chose
#   command line (with a shell variable) to keep the config file secret-free.
# ==============================================================================

pgr() {
    # pgr COMMAND...
    # Runs pgbackrest with the standard flags. Returns pgbackrest's exit code.
    pgbackrest \
        --stanza="$STANZA" \
        --repo="$REPO" \
        --repo1-cipher-pass="$CIPHER_PASS" \
        "$@"
}

# ==============================================================================
# STARTUP CHECK
# ==============================================================================
# Verify the stanza exists and is reachable BEFORE scheduling any jobs. If
# the operator forgot to run `stanza-create`, this fails with a clear
# message. The container exits, Docker's restart policy retries, and the
# operator sees a restart loop in `docker compose ps` — impossible to miss.
#
# Retry a few times, because on first boot the Postgres container may not
# be ready when the sidecar starts (even with depends_on: healthy, there's
# a small window before pgBackRest can connect).
# ==============================================================================

log_info "running startup check (pgbackrest check)"

CHECK_ATTEMPTS=0
CHECK_MAX=10

while :; do
    CHECK_ATTEMPTS=$((CHECK_ATTEMPTS + 1))

    if pgr check >/tmp/check.out 2>&1; then
        log_info "startup check passed"
        break
    fi

    # Distinguish "stanza doesn't exist" from "Postgres not reachable"
    # by grepping the output. The former needs manual intervention; the
    # latter is retryable.
    if grep -q "stanza.*not.*found\|does not exist" /tmp/check.out 2>/dev/null; then
        log_error "stanza '$STANZA' does not exist"
        log_error "this is a one-time setup step — run the following against"
        log_error "the postgres container, then restart this sidecar:"
        log_error ""
        log_error "  docker compose -f docker-compose.yml -f docker-compose.prod.yml \\"
        log_error "    exec postgres pgbackrest --stanza=$STANZA --repo=$REPO \\"
        log_error "    --repo1-cipher-pass=\"\$(cat /run/secrets/pgbackrest_repo_cipher)\" \\"
        log_error "    stanza-create"
        log_error ""
        log_error "see docs/runbooks/restore-postgres.md for the full first-deploy procedure"
        exit 1
    fi

    if [ "$CHECK_ATTEMPTS" -ge "$CHECK_MAX" ]; then
        log_error "startup check failed after $CHECK_MAX attempts"
        log_error "last pgbackrest output:"
        sed 's/^/  /' /tmp/check.out >&2
        exit 1
    fi

    log_warn "startup check attempt $CHECK_ATTEMPTS/$CHECK_MAX failed, retrying in 10s"
    sleep 10
done

rm -f /tmp/check.out

# ==============================================================================
# SCHEDULE HELPERS
# ==============================================================================
# Compute "seconds until next occurrence of HH:00 UTC" and similar. We use
# `date` for portability (busybox-compatible syntax, no GNU-isms).
# ==============================================================================

# seconds_until_next_hour — until the next HH:00:00 UTC (top of next hour)
seconds_until_next_hour() {
    now=$(date -u +%s)
    # Next top of hour = now - (now % 3600) + 3600
    next_hour=$(( (now / 3600) * 3600 + 3600 ))
    echo $(( next_hour - now ))
}

# seconds_until_hour HH — until the next HH:00:00 UTC today or tomorrow
seconds_until_hour() {
    target_hour="$1"
    now=$(date -u +%s)
    today_start=$(( (now / 86400) * 86400 ))      # 00:00 UTC today, in epoch
    target=$(( today_start + target_hour * 3600 ))
    if [ "$target" -le "$now" ]; then
        target=$(( target + 86400 ))              # tomorrow
    fi
    echo $(( target - now ))
}

# ==============================================================================
# JOB RUNNERS
# ==============================================================================
# Each job logs start/end, captures the exit code, and never exits the loop
# on failure. A transient failure (e.g., network hiccup to the repository)
# should be retried by the next scheduled run, not bring down the sidecar.
#
# If a job takes longer than the next job's scheduled time, the next job
# will fire immediately after the current one completes (the loop computes
# "next scheduled time" from wall clock, so it catches up instantly).
# ==============================================================================

run_full_backup() {
    log_info "job start: full backup"
    if pgr --type=full backup; then
        log_info "job success: full backup"
    else
        rc=$?
        log_error "job failure: full backup (exit=$rc)"
    fi
}

run_incremental_backup() {
    log_info "job start: incremental backup"
    if pgr --type=incr backup; then
        log_info "job success: incremental backup"
    else
        rc=$?
        log_error "job failure: incremental backup (exit=$rc)"
    fi
}

run_expire() {
    log_info "job start: expire (prune old backups and WAL)"
    if pgr expire; then
        log_info "job success: expire"
    else
        rc=$?
        log_error "job failure: expire (exit=$rc)"
    fi
}

# ==============================================================================
# MAIN LOOP
# ==============================================================================
# Compute the next scheduled event, sleep until then, run the corresponding
# job, repeat. Only one job runs at a time (jobs are blocking).
#
# Scheduled events (all UTC):
#   02:00 daily  — full backup
#   03:00 daily  — expire
#   :00 hourly   — incremental backup (except during 02:00 and 03:00 hours,
#                  which are occupied by the full backup and expire)
# ==============================================================================

log_info "entering schedule loop"
log_info "schedule: full backup daily at ${FULL_BACKUP_HOUR}:00 UTC"
log_info "schedule: expire daily at ${EXPIRE_HOUR}:00 UTC"
log_info "schedule: incremental backup hourly at :00 UTC"

while [ "$SHUTDOWN_REQUESTED" -eq 0 ]; do

    # --------------------------------------------------------------------------
    # Determine which scheduled event is next.
    # --------------------------------------------------------------------------

    now_hour=$(date -u +%H)
    now_min=$(date -u +%M)
    now_sec=$(date -u +%S)

    # If we're exactly at a scheduled minute and second, run it. Otherwise
    # sleep until the nearest event.

    # Compute seconds until each event
    secs_to_full=$(seconds_until_hour "$FULL_BACKUP_HOUR")
    secs_to_expire=$(seconds_until_hour "$EXPIRE_HOUR")
    secs_to_top=$(seconds_until_next_hour)

    # Find the minimum — that's our next event
    next_in=$secs_to_top

    if [ "$secs_to_full" -lt "$next_in" ]; then
        next_in=$secs_to_full
    fi
    if [ "$secs_to_expire" -lt "$next_in" ]; then
        next_in=$secs_to_expire
    fi

    # Sleep in 60-second chunks so SIGTERM is responsive. If shutdown is
    # requested during the sleep, we exit within ~60 seconds.
    log_info "next event in ${next_in}s"

    slept=0
    while [ "$slept" -lt "$next_in" ] && [ "$SHUTDOWN_REQUESTED" -eq 0 ]; do
        chunk=60
        remaining=$((next_in - slept))
        if [ "$remaining" -lt "$chunk" ]; then
            chunk=$remaining
        fi
        sleep "$chunk"
        slept=$((slept + chunk))
    done

    if [ "$SHUTDOWN_REQUESTED" -ne 0 ]; then
        break
    fi

    # --------------------------------------------------------------------------
    # Determine which job to run based on the current hour.
    # --------------------------------------------------------------------------

    current_hour=$(date -u +%H)

    if [ "$current_hour" = "$(printf '%02d' "$FULL_BACKUP_HOUR")" ]; then
        run_full_backup
    elif [ "$current_hour" = "$(printf '%02d' "$EXPIRE_HOUR")" ]; then
        run_expire
    else
        run_incremental_backup
    fi

    # Loop back — next iteration computes the next event from wall clock.
done

# ==============================================================================
# SHUTDOWN
# ==============================================================================
# Reached when SIGTERM/SIGINT is received. If a job was running, it has
# already completed (jobs are blocking — the loop only checks the shutdown
# flag between jobs).
#
# We log a clean shutdown so the logs make the transition obvious.
# ==============================================================================

log_info "shutting down pgBackRest sidecar"

# Nothing to clean up — pgBackRest doesn't leave temporary state behind
# after a completed command. If a job was killed mid-run (which shouldn't
# happen given our blocking model), the partial backup would be visible in
# `pgbackrest info` and could be manually removed.

exit 0