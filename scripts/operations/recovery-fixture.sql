-- Disposable acceptance data only. Uses actual constraints and durable queues.
BEGIN;
CREATE TABLE public.operations_recovery_marker(id integer PRIMARY KEY, purpose text NOT NULL);
INSERT INTO public.operations_recovery_marker VALUES (1,'included-in-full-backup');
INSERT INTO inventory.stock_level(id,sku,warehouse_id,quantity_on_hand,quantity_reserved)
VALUES('70000000-0000-0000-0000-000000000001','RECOVERY-SKU','recovery-warehouse',10,1);
INSERT INTO inventory.reservation(id,sku,warehouse_id,quantity,order_id,status)
VALUES('70000000-0000-0000-0000-000000000002','RECOVERY-SKU','recovery-warehouse',1,'70000000-0000-0000-0000-000000000003','ACTIVE');
INSERT INTO payments.payment(id,order_id,customer_id,amount,currency,status,idempotency_key)
VALUES('70000000-0000-0000-0000-000000000004','70000000-0000-0000-0000-000000000003','70000000-0000-0000-0000-000000000005',10,'USD','PENDING','operations-recovery-payment');
INSERT INTO orders.purchase_order(id,order_number,customer_id,customer_email,status,subtotal_amount,total_amount,currency,
 shipping_line1,shipping_city,shipping_postal_code,shipping_country,payment_id)
VALUES('70000000-0000-0000-0000-000000000003','ORD-RECOVERY-0001','70000000-0000-0000-0000-000000000005','recovery@example.invalid','DRAFT',10,10,'USD','Fixture','Test','10001','US','70000000-0000-0000-0000-000000000004');
INSERT INTO orders.order_line(id,order_id,product_id,product_sku,product_name,unit_price_amount,unit_price_currency,quantity,line_total,
 reservation_id,reservation_warehouse_id,reservation_verified)
VALUES('70000000-0000-0000-0000-000000000006','70000000-0000-0000-0000-000000000003','70000000-0000-0000-0000-000000000008','RECOVERY-SKU','Recovery fixture',10,'USD',1,10,'70000000-0000-0000-0000-000000000002','recovery-warehouse',true);
INSERT INTO orders.checkout_attempt(order_id,status) VALUES('70000000-0000-0000-0000-000000000003','ACCEPTED');
INSERT INTO orders.outbox_event(id,aggregate_id,aggregate_type,event_type,topic,payload,status,attempts)
VALUES('70000000-0000-0000-0000-000000000007','70000000-0000-0000-0000-000000000003','ORDER','ORDER_CREATED','orders.events.order',
 '{"eventId":"70000000-0000-0000-0000-000000000007","eventType":"ORDER_CREATED","aggregateId":"70000000-0000-0000-0000-000000000003","schemaVersion":1,"occurredAt":"2026-11-25T00:00:00Z","data":{"orderId":"70000000-0000-0000-0000-000000000003","customerId":"70000000-0000-0000-0000-000000000005"}}','FAILED',10);
INSERT INTO notifications.notification_dispatch(id,transport,destination,message_key,payload,state)
VALUES('70000000-0000-0000-0000-000000000009','REDIS','notifications:orders','70000000-0000-0000-0000-000000000003','{}','PENDING');
COMMIT;
