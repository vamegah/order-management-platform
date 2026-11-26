\set ECHO none
SET log_statement = 'none';
SET log_min_duration_statement = -1;
SET log_min_error_statement = 'panic';
-- Run as database owner after application migrations. Password comes from a mounted file.
-- psql --set ON_ERROR_STOP=1 --file infrastructure/postgres/monitoring.sql
SELECT 'CREATE ROLE omp_monitor LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='omp_monitor') \gexec
SELECT format('ALTER ROLE omp_monitor PASSWORD %L', trim(trailing E'\r\n' FROM pg_read_file('/run/secrets/postgres_exporter_password',0,4096))) \gexec
GRANT pg_monitor TO omp_monitor;
GRANT CONNECT ON DATABASE omp TO omp_monitor;
CREATE SCHEMA IF NOT EXISTS operations;
REVOKE ALL ON SCHEMA operations FROM PUBLIC;
CREATE OR REPLACE VIEW operations.queue_backlog WITH (security_barrier=true) AS
 SELECT 'notifications'::text AS service,'webhook_dlq'::text AS queue,
        count(*)::double precision AS pending,
        coalesce(extract(epoch FROM now()-min(received_at)),0)::double precision AS oldest_seconds
   FROM notifications.webhook_delivery_dlq WHERE replayed_at IS NULL
 UNION ALL
 SELECT 'notifications','dispatch',count(*)::double precision,
        coalesce(extract(epoch FROM now()-min(created_at)),0)::double precision
   FROM notifications.notification_dispatch WHERE state <> 'PUBLISHED'
 UNION ALL
 SELECT 'notifications','quarantine',count(*)::double precision,
        coalesce(extract(epoch FROM now()-min(received_at)),0)::double precision
   FROM notifications.domain_event_quarantine WHERE replayed_at IS NULL;
REVOKE ALL ON operations.queue_backlog FROM PUBLIC;
GRANT USAGE ON SCHEMA operations TO omp_monitor;
GRANT SELECT ON operations.queue_backlog TO omp_monitor;
-- Do not grant SELECT on any business payload table.
