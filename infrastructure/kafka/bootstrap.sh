#!/bin/bash
set -euo pipefail
admin=(--bootstrap-server kafka:9092 --command-config /run/secrets/kafka_admin_properties)
topics=(orders.events.order inventory.events.reservation payments.events.payment webhooks.outbound.deliveries webhooks.outbound.deliveries.retry-1 webhooks.outbound.deliveries.retry-2 webhooks.outbound.deliveries.retry-3 webhooks.outbound.deliveries.retry-4 webhooks.outbound.deliveries.retry-5 webhooks.outbound.deliveries.dlq)
for topic in "${topics[@]}"; do
    /opt/kafka/bin/kafka-topics.sh "${admin[@]}" --create --if-not-exists --topic "$topic" --partitions 6 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
done
for pair in orders_svc:orders.events.order inventory_svc:inventory.events.reservation payments_svc:payments.events.payment; do
    principal="${pair%%:*}"; topic="${pair#*:}"
    /opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal "User:$principal" --operation Write --operation Describe --topic "$topic" >/dev/null
    /opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal "User:$principal" --operation IdempotentWrite --cluster >/dev/null
done
for topic in orders.events.order inventory.events.reservation payments.events.payment; do
    /opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal User:notifications_svc --operation Read --operation Describe --topic "$topic" >/dev/null
done
/opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal User:notifications_svc --operation Read --operation Write --operation Describe --topic webhooks. --resource-pattern-type prefixed >/dev/null
/opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal User:notifications_svc --operation Read --operation Describe --group notification --resource-pattern-type prefixed >/dev/null
/opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal User:notifications_svc --operation IdempotentWrite --cluster >/dev/null
/opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal User:omp_monitor --operation Describe --topic '*' --group '*' >/dev/null
/opt/kafka/bin/kafka-acls.sh "${admin[@]}" --add --allow-principal User:omp_monitor --operation Describe --cluster >/dev/null
printf 'Production topics and scoped client ACLs provisioned.\n'
