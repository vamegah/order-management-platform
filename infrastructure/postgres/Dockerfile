# ==============================================================================
# Order Management Platform — Custom PostgreSQL Image
# ------------------------------------------------------------------------------
# FILENAME : infrastructure/postgres/Dockerfile
# BASE     : postgres:16.4-alpine (same as dev, see docker-compose.yml)
# PURPOSE  : Extend the official Postgres image with pgBackRest, so that
#            Postgres's archive_command (see postgresql.prod.conf, section 3)
#            can invoke `pgbackrest --stanza=omp archive-push %p` to
#            continuously archive WAL segments for point-in-time recovery.
#
# WHY A CUSTOM IMAGE
#   The official postgres:16-alpine image intentionally contains ONLY Postgres
#   and its runtime dependencies. It does NOT include pgBackRest. Without
#   pgBackRest in the container, the archive_command would fail on every WAL
#   segment, WAL would accumulate, and the data directory would fill up.
#
#   This image adds pgBackRest and nothing else. It is a drop-in replacement
#   for postgres:16-alpine — same entrypoint, same env vars, same behaviour
#   for every other concern.
#
# USAGE
#   In docker-compose.prod.yml, the `postgres` service declares:
#       build:
#         context: ./infrastructure/postgres
#         dockerfile: Dockerfile
#   instead of `image: postgres:16.4-alpine`.
#
#   The dev stack keeps using the plain postgres:16.4-alpine image (no
#   pgBackRest needed locally; archive_mode = off in dev).
#
# BUILD
#   docker build -t omp/postgres:16.4 infrastructure/postgres/
#
#   Or via compose (prod overlay):
#     docker compose -f docker-compose.yml -f docker-compose.prod.yml build postgres
#
# VERIFY
#   docker run --rm omp/postgres:16.4 pgbackrest version
#   # Expected output: pgBackRest 2.54.0
#
# ==============================================================================

# ------------------------------------------------------------------------------
# Base image
# ------------------------------------------------------------------------------
# Same tag as dev. This is deliberate: dev and prod must run the same Postgres
# version, same Alpine base, same set of built-in Postgres extensions. Any
# difference here would cause "works on my machine" bugs that only appear
# when promoting to production.
#
# To pin for fully reproducible builds, use a digest:
#   FROM postgres:16.4-alpine@sha256:<digest>
# We use the floating tag so Alpine security patches are picked up on rebuild.
# The deploy pipeline controls when to rebuild, and image digests are recorded
# in the deployment log for reproducibility.
# ------------------------------------------------------------------------------
FROM postgres:16.4-alpine

# ------------------------------------------------------------------------------
# Metadata
# ------------------------------------------------------------------------------
# Standard OCI labels. Helpful for `docker inspect`, image scanning tools,
# and auditing which images are running where.
# ------------------------------------------------------------------------------
LABEL org.opencontainers.image.title="OMP PostgreSQL"
LABEL org.opencontainers.image.description="PostgreSQL 16.4 with pgBackRest for WAL archiving"
LABEL org.opencontainers.image.vendor="Order Management Platform"
LABEL org.opencontainers.image.base.name="docker.io/library/postgres:16.4-alpine"
LABEL org.opencontainers.image.source="https://github.com/example/order-management-platform"

# ------------------------------------------------------------------------------
# Install pgBackRest
# ------------------------------------------------------------------------------
# The Alpine package `pgbackrest` provides the binary and its runtime
# dependencies. We pin to 2.54.0 to match the pgbackrest/pgbackrest:2.54.0
# sidecar image (see docker-compose.prod.yml).
#
# Alpine's package manager (apk) has multiple repositories. By default,
# `apk add pgbackrest` installs from `main`. Version 2.54.0 is available in
# Alpine 3.20+ (which postgres:16.4-alpine is based on). If a future Alpine
# release drops 2.54.0, either:
#   - Pin to a slightly newer version (2.54.1, 2.55.x) and update the
#     sidecar image tag in docker-compose.prod.yml to match, OR
#   - Install from Alpine's `community` repository, which often carries
#     more frequent updates than `main`.
#
# We do NOT install any additional packages. pgbackrest pulls in exactly
# what it needs (openssl, libssh2, libxml2, pcre2, zstd, lz4, etc.). Adding
# `bash`, `curl`, or `tini` would bloat the image and expand the attack
# surface for no benefit.
#
# --no-cache: don't leave the apk package index in the image. Saves ~5MB and
# removes a source of stale metadata.
# ------------------------------------------------------------------------------
RUN apk add --no-cache \
        pgbackrest=2.54.0-r0

# ------------------------------------------------------------------------------
# Verify the installation
# ------------------------------------------------------------------------------
# Fail the build if pgbackrest isn't runnable or is the wrong version. This
# is a build-time guarantee: if the image builds, the binary works.
#
# Without this check, a broken install would only surface at runtime when
# Postgres tries to archive its first WAL segment — a much worse time to
# discover the problem.
#
# The `grep` ensures we got exactly the version we asked for. Alpine has
# been known to resolve slightly different versions when the exact pin
# isn't available in the repo index.
# ------------------------------------------------------------------------------
RUN set -eux; \
    pgbackrest version; \
    pgbackrest version | grep -q "2.54.0" || \
        { echo "ERROR: expected pgBackRest 2.54.0, got: $(pgbackrest version)"; exit 1; }

# ------------------------------------------------------------------------------
# Add the archive-push wrapper
# ------------------------------------------------------------------------------
# Postgres invokes `archive_command` for every completed WAL segment (see
# File 9's postgresql.prod.conf). The command must:
#   1. Be a single, robust executable (no inline shell, no $() substitution).
#   2. Read the cipher passphrase from /run/secrets/pgbackrest_repo_cipher.
#   3. Pass it to pgbackrest via --repo1-cipher-pass.
#   4. Return 0 on success, non-zero on failure.
#
# The wrapper (File 9b) encapsulates all of that. We copy it into the image
# and make it executable.
#
# Permissions 0755: readable and executable by everyone, writable only by
# root. The postgres user (uid 999) needs to execute it; nobody needs to
# modify it at runtime.
#
# We do NOT mount this file from the host at runtime, because:
#   - It must be present before Postgres starts (the first WAL segment
#     could be archived within seconds of startup).
#   - Embedding it in the image makes the wrapper's version tied to the
#     image tag — no drift between the binary and the wrapper.
#   - It contains no secrets, so there's no reason to externalize it.
# ------------------------------------------------------------------------------
COPY pgbackrest-archive-push.sh /usr/local/bin/pgbackrest-archive-push
RUN chmod 0755 /usr/local/bin/pgbackrest-archive-push

# ------------------------------------------------------------------------------
# Verify the wrapper is present and executable
# ------------------------------------------------------------------------------
# Same principle as the pgbackrest version check: fail the build if the
# wrapper isn't correctly installed. A missing wrapper would only surface
# when Postgres tries to archive its first WAL segment — much worse timing.
# ------------------------------------------------------------------------------
RUN set -eux; \
    test -x /usr/local/bin/pgbackrest-archive-push || \
        { echo "ERROR: pgbackrest-archive-push wrapper is missing or not executable"; exit 1; }

# ------------------------------------------------------------------------------
# Prepare config directory
# ------------------------------------------------------------------------------
# pgBackRest reads its configuration from /etc/pgbackrest/pgbackrest.conf by
# default. We create the directory here so the compose overlay can mount the
# config file into it read-only without needing to create the directory.
#
# Ownership: postgres:postgres (uid/gid 999 in the official image). The
# archive_command runs as the postgres user, so the config file must be
# readable by that user. The file itself is mounted read-only by compose;
# we just own the directory.
#
# Mode 0750: owner and group can read/execute, others can't. The postgres
# user is the owner, so this is sufficient. Any pgBackRest process runs as
# postgres, never as root.
# ------------------------------------------------------------------------------
RUN install -d -m 0750 -o postgres -g postgres /etc/pgbackrest

# ------------------------------------------------------------------------------
# Prepare the pgBackRest repository mount point (inside the container)
# ------------------------------------------------------------------------------
# In dev/standalone usage, someone might want to bind-mount a repository
# directly into this container (instead of using the sidecar). This directory
# gives them a well-known path to mount to.
#
# In our production setup, the sidecar (see docker-compose.prod.yml) owns
# the repository. The Postgres container writes WAL files to a shared volume
# that the sidecar reads from. This directory is a convenience fallback.
#
# Mode 0700: only postgres can access. The repository contains encrypted
# backups and WAL segments — no other user in the container should see them.
# ------------------------------------------------------------------------------
RUN install -d -m 0700 -o postgres -g postgres /var/lib/pgbackrest

# ------------------------------------------------------------------------------
# Preserve the base image's healthcheck, entrypoint, and CMD
# ------------------------------------------------------------------------------
# The official postgres image's entrypoint:
#   /usr/local/bin/docker-entrypoint.sh
# Does the following:
#   1. Runs as root, ensures /var/lib/postgresql/data has correct ownership.
#   2. If PGDATA is empty: runs initdb, applies any scripts in
#      /docker-entrypoint-initdb.d/, sets up passwords.
#   3. If PGDATA is non-empty: skips init, just starts Postgres.
#   4. Drops privileges to the postgres user (uid 999).
#   5. Execs `postgres` with any args passed (we pass `-c config_file=...`).
#
# We do NOT override ENTRYPOINT or CMD. The custom image behaves exactly
# like the official image for everything except having pgbackrest available.
#
# Similarly, we do NOT override the base image's default environment
# variables (PGDATA, PG_MAJOR, PG_VERSION, etc.). They're already correct.
# ------------------------------------------------------------------------------

# ------------------------------------------------------------------------------
# Build-time verification (optional, but recommended for CI)
# ------------------------------------------------------------------------------
# Print a summary of what's in the image. This runs during `docker build`
# and appears in the build log — helpful when scanning CI output for
# "what did this image actually contain?"
#
# We could add more checks here (e.g., `pgbackrest check` against a dummy
# config), but that would require a valid config file at build time, which
# we don't have. The runtime config comes from File 10 (pgbackrest.conf),
# mounted by the compose overlay.
# ------------------------------------------------------------------------------
RUN set -eux; \
    echo "===== Image summary ====="; \
    echo "PostgreSQL: $(postgres --version)"; \
    echo "pgBackRest: $(pgbackrest version)"; \
    echo "User:       $(id postgres)"; \
    echo "========================="

# ------------------------------------------------------------------------------
# No EXPOSE, no HEALTHCHECK, no USER directive
# ------------------------------------------------------------------------------
# EXPOSE      : The base image already exposes 5432. We don't change it.
# HEALTHCHECK : Defined in docker-compose.yml (a policy of the deployment,
#               not a property of the image). Keeping it out lets different
#               deployments use different healthchecks.
# USER        : The base image's entrypoint handles user switching (root → 
#               postgres after init). Overriding USER here would break that
#               flow.
# ------------------------------------------------------------------------------