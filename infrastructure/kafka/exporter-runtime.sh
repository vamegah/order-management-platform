#!/bin/sh
set -eu
SASL_USER_PASSWORD="$(cat /run/secrets/kafka_monitor_password)"
export SASL_USER_PASSWORD
exec /bin/kafka_exporter --kafka.server=kafka:9092 --kafka.server=kafka-2:9092 --kafka.server=kafka-3:9092 --sasl.enabled --sasl.username=omp_monitor --sasl.mechanism=scram-sha512 --topic.workers=4
