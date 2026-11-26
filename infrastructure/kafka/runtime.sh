#!/bin/bash
# SCRAM bootstrap records and broker credentials are mounted files, not Docker environment values.
set -euo pipefail
umask 077
node="${KAFKA_NODE_ID:?}"
hostname="${KAFKA_ADVERTISED_HOST:?}"
[[ "$node" =~ ^[123]$ && "$hostname" =~ ^kafka(-[23])?$ ]] || exit 2
password="$(cat /run/secrets/kafka_broker_password)"
[[ "$password" =~ ^[A-Za-z0-9_=-]{16,128}$ ]] || exit 2
mkdir -p /var/lib/kafka/data
config="$(mktemp /run/omp/kafka.XXXXXX.properties)"
cat > "$config" <<EOF
process.roles=broker,controller
node.id=$node
controller.quorum.voters=1@kafka:9093,2@kafka-2:9093,3@kafka-3:9093
controller.listener.names=CONTROLLER
listeners=INTERNAL://:9092,CONTROLLER://:9093
advertised.listeners=INTERNAL://$hostname:9092
listener.security.protocol.map=INTERNAL:SASL_PLAINTEXT,CONTROLLER:SASL_PLAINTEXT
inter.broker.listener.name=INTERNAL
sasl.enabled.mechanisms=SCRAM-SHA-512,PLAIN
listener.name.internal.sasl.enabled.mechanisms=SCRAM-SHA-512
listener.name.controller.sasl.enabled.mechanisms=PLAIN
sasl.mechanism.controller.protocol=PLAIN
listener.name.controller.plain.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="broker" password="$password" user_broker="$password";
sasl.mechanism.inter.broker.protocol=SCRAM-SHA-512
listener.name.internal.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="broker" password="$password";
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:broker;User:omp_admin
allow.everyone.if.no.acl.found=false
log.dirs=/var/lib/kafka/data
num.partitions=6
default.replication.factor=3
min.insync.replicas=2
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2
auto.create.topics.enable=false
unclean.leader.election.enable=false
log.retention.hours=168
EOF
unset password
if [ ! -f /var/lib/kafka/data/meta.properties ]; then
    arguments=()
    while IFS= read -r record; do [ -z "$record" ] || arguments+=(--add-scram "$record"); done < /run/secrets/kafka_scram_bootstrap
    /opt/kafka/bin/kafka-storage.sh format --cluster-id="$(cat /run/secrets/kafka_cluster_id)" -c "$config" "${arguments[@]}" >/dev/null
fi
exec /opt/kafka/bin/kafka-server-start.sh "$config"
