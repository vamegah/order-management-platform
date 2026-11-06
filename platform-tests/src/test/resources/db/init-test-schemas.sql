-- init-test-schemas.sql
-- Creates the test roles and schemas used by the shared Postgres container.
-- Runs once on container start (before Flyway).

CREATE ROLE notifications_svc LOGIN PASSWORD 'notifications_svc';
CREATE ROLE payments_svc     LOGIN PASSWORD 'payments_svc';
CREATE ROLE orders_svc       LOGIN PASSWORD 'orders_svc';
CREATE ROLE inventory_svc    LOGIN PASSWORD 'inventory_svc';

CREATE SCHEMA IF NOT EXISTS notifications AUTHORIZATION notifications_svc;
CREATE SCHEMA IF NOT EXISTS payments      AUTHORIZATION payments_svc;
CREATE SCHEMA IF NOT EXISTS orders        AUTHORIZATION orders_svc;
CREATE SCHEMA IF NOT EXISTS inventory     AUTHORIZATION inventory_svc;
CREATE SCHEMA IF NOT EXISTS outbox        AUTHORIZATION omp_test;

-- Grant the superuser (omp_test) access to everything so test fixtures can
-- inspect rows across schemas.
GRANT ALL ON SCHEMA notifications TO omp_test;
GRANT ALL ON SCHEMA payments      TO omp_test;
GRANT ALL ON SCHEMA orders        TO omp_test;
GRANT ALL ON SCHEMA inventory     TO omp_test;
GRANT ALL ON SCHEMA outbox        TO omp_test;

ALTER DEFAULT PRIVILEGES IN SCHEMA notifications GRANT ALL ON TABLES TO omp_test;
ALTER DEFAULT PRIVILEGES IN SCHEMA payments      GRANT ALL ON TABLES TO omp_test;
ALTER DEFAULT PRIVILEGES IN SCHEMA orders        GRANT ALL ON TABLES TO omp_test;
ALTER DEFAULT PRIVILEGES IN SCHEMA inventory     GRANT ALL ON TABLES TO omp_test;