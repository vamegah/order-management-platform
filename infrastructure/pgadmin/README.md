# pgAdmin — Local Development Configuration

This directory contains the pgAdmin container's pre-configured server list
for local development.

## Files

- `servers.json` — imported by pgAdmin on first startup. Pre-populates the
  server list so developers don't have to manually add the connection.

## How it works

The `pgadmin` service in `docker-compose.yml` (dev only) mounts this directory:

    volumes:
      - ./infrastructure/pgadmin/servers.json:/pgadmin4/servers.json:ro

On first boot, pgAdmin reads `/pgadmin4/servers.json` and creates the server
entries it finds. On subsequent boots, pgAdmin's own database (stored in the
container's writable layer) takes precedence — the pre-configured entries
are not re-imported, so any changes a developer makes in the UI persist for
the container's lifetime.

To reset to the file's defaults, wipe the container:

    docker compose rm -f pgadmin
    docker compose up -d pgadmin

## The `servers.json` format

The file must be valid JSON with two top-level keys:

    {
      "Servers": { "<id>": {...}, ... },
      "ServerGroups": { "<id>": {...}, ... }
    }

### `Servers.<id>`

| Field                | Type    | Notes |
|----------------------|---------|-------|
| `Name`               | string  | Display name in the pgAdmin tree. Must be unique. |
| `Group`              | string  | Must match a `ServerGroups.<id>.Name`. |
| `Host`               | string  | Docker service name (`postgres`) — resolves on the Docker network. |
| `Port`               | integer | **Internal** container port (5432), not the host-mapped port. |
| `MaintenanceDB`      | string  | DB pgAdmin connects to for system queries. Use `postgres`, not the app DB. |
| `Username`           | string  | Must match `POSTGRES_SUPERUSER` in `.env`. |
| `SSLMode`            | string  | `prefer`, `require`, `disable`, `verify-ca`, `verify-full`. |
| `Comment`            | string  | Free-form. Shows in the server properties dialog. |
| `ConnectionParameters` | object | Extra libpq connection params. See below. |
| `Password`           | string  | Plaintext. **Dev only.** Must match `POSTGRES_SUPERPASSWORD` in `.env`. |
| `SavePassword`       | boolean | If `true`, pgAdmin stores the password (encrypted in its internal DB). |

### `ConnectionParameters`

Passed through to libpq. Common keys:

- `sslmode` — as above.
- `connect_timeout` — seconds. Default 10.
- `application_name` — appears in Postgres's `pg_stat_activity`. Useful for
  identifying pgAdmin sessions in `pg_stat_activity` and logs.

### `ServerGroups.<id>`

| Field     | Type   | Notes |
|-----------|--------|-------|
| `Name`    | string | Display name. Must match any `Servers.<id>.Group` values. |
| `Comment` | string | Free-form. |

## Keeping this file in sync with `.env`

Three values in `servers.json` duplicate values from `.env.example`:

| `servers.json` field | `.env.example` variable | Default value |
|----------------------|-------------------------|---------------|
| `Username`           | `POSTGRES_SUPERUSER`    | `omp_admin` |
| `Password`           | `POSTGRES_SUPERPASSWORD`| `local-dev-only-change-me` |
| `MaintenanceDB`      | `POSTGRES_DB`           | `postgres` (system DB, not app DB) |

**If you change any of these in `.env`, update `servers.json` to match.**
Otherwise pgAdmin will show "connection failed" when you try to open the
server.

This duplication is deliberate: pgAdmin's `servers.json` doesn't support
environment variable substitution. The alternative — an entrypoint script
that templates the file from env — adds complexity for zero security benefit
(the "secret" here is a public placeholder).

## Security notes

- **This file is dev-only.** The `pgadmin` service is not deployed in prod
  (see `docker-compose.prod.yml`, which scales it to zero).
- **The password in this file is a placeholder.** It matches the placeholder
  in `.env.example`. Neither file contains real credentials.
- **The password is stored in plaintext.** pgAdmin's JSON import format
  requires it. In production, pgAdmin would be protected by an auth proxy
  (Traefik + basic auth) AND would be configured without stored passwords.
- **This directory is safe to commit.** It contains no secrets.

## Troubleshooting

**pgAdmin shows "connection refused" when I try to open the server.**

- Verify Postgres is healthy: `docker compose ps postgres` should show
  `(healthy)`.
- Verify the hostname: `servers.json` uses `postgres` (Docker DNS). From
  inside the pgAdmin container, `getent hosts postgres` should resolve to
  Postgres's IP. From your laptop (outside Docker), `postgres` won't resolve
  — this is expected.
- Verify the credentials match `.env`.

**Changes to `servers.json` don't take effect.**

pgAdmin imports the file only on first boot. To re-import:

    docker compose rm -sf pgadmin
    docker compose up -d pgadmin

Alternatively, edit the server in pgAdmin's UI — the change persists in
pgAdmin's internal DB for the container's lifetime.

**I don't see my schema changes after a Flyway migration (Phase 1+).**

pgAdmin caches schema information. Right-click the database in the tree and
choose "Refresh" (or press F5). The underlying data is fine; it's the display
that's stale.

**The `OMP` server group appears empty.**

The `Group` field in `Servers.<id>` must exactly match the `Name` field in
`ServerGroups.<id>`. Whitespace and case matter. If they don't match,
pgAdmin puts the server in a default group instead.

## References

- pgAdmin server import/export documentation:
  https://www.pgadmin.org/docs/pgadmin4/latest/import_export_servers.html
- libpq connection parameters (used by `ConnectionParameters`):
  https://www.postgresql.org/docs/16/libpq-connect.html