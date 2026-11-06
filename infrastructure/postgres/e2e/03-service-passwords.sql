-- Development acceptance only. The e2e overlay supplies these environment values.
-- psql quotes every value as a SQL literal; credentials never enter shell arguments.
\set ECHO none
SET log_statement = 'none';
SET log_min_duration_statement = -1;
\getenv product_password PRODUCT_SERVICE_DB_PASSWORD
\getenv customer_password CUSTOMER_SERVICE_DB_PASSWORD
\getenv order_password ORDER_SERVICE_DB_PASSWORD
\getenv inventory_password INVENTORY_SERVICE_DB_PASSWORD
\getenv payment_password PAYMENT_SERVICE_DB_PASSWORD
\getenv notification_password NOTIFICATION_SERVICE_DB_PASSWORD
ALTER ROLE products_svc PASSWORD :'product_password';
ALTER ROLE customers_svc PASSWORD :'customer_password';
ALTER ROLE orders_svc PASSWORD :'order_password';
ALTER ROLE inventory_svc PASSWORD :'inventory_password';
ALTER ROLE payments_svc PASSWORD :'payment_password';
ALTER ROLE notifications_svc PASSWORD :'notification_password';
