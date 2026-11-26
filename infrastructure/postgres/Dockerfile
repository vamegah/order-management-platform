# PostgreSQL 16.16 and the tested pgBackRest package. Runtime secrets are never built into this image.
FROM postgres:16-alpine@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663
ARG PGBACKREST_VERSION=2.58.0-r0
# Optional local corporate roots are a BuildKit input, never a private key.
RUN --mount=type=secret,id=build_ca \
    if [ -f /run/secrets/build_ca ]; then cat /run/secrets/build_ca >> /etc/ssl/cert.pem; fi; \
    apk add --no-cache "pgbackrest=${PGBACKREST_VERSION}" && pgbackrest version
RUN install -d -m 0700 -o postgres -g postgres /var/lib/pgbackrest /var/spool/pgbackrest /var/log/pgbackrest \
    && install -d -m 0750 -o postgres -g postgres /etc/pgbackrest
COPY --chmod=755 pgbackrest-command.sh /usr/local/bin/omp-pgbackrest
LABEL org.opencontainers.image.source="https://github.com/vamegah/order-management-platform" \
      org.opencontainers.image.title="OMP PostgreSQL with encrypted WAL recovery"
