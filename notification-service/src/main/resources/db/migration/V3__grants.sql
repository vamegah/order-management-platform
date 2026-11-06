-- V3__grants.sql
-- Ensure the notifications_svc role has the privileges it needs.
-- Safe to run multiple times.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notifications_svc') THEN
        GRANT USAGE ON SCHEMA notifications TO notifications_svc;
        GRANT CREATE ON SCHEMA notifications TO notifications_svc;

        GRANT ALL ON ALL TABLES IN SCHEMA notifications TO notifications_svc;
        GRANT ALL ON ALL SEQUENCES IN SCHEMA notifications TO notifications_svc;

        ALTER DEFAULT PRIVILEGES IN SCHEMA notifications
            GRANT ALL ON TABLES TO notifications_svc;
        ALTER DEFAULT PRIVILEGES IN SCHEMA notifications
            GRANT ALL ON SEQUENCES TO notifications_svc;

        ALTER ROLE notifications_svc SET search_path TO notifications, public;
    END IF;
END
$$;