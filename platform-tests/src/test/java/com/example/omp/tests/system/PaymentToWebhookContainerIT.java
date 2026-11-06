package com.example.omp.tests.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.omp.notification.webhook.config.WebhookKafkaTopics;
import com.example.omp.shared.events.KafkaTopics;
import com.example.omp.testsupport.ContainerSubscriber;
import com.example.omp.testsupport.TestJwtIssuer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Packaged services in separate JVMs/containers, with verified JWTs and real
 * PostgreSQL/Kafka/Redis.
 */
class PaymentToWebhookContainerIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> redis;
    private static GenericContainer<?> payments;
    private static GenericContainer<?> notifications;
    private static TestJwtIssuer issuer;
    private static GenericContainer<?> subscriber;
    private static String token;

    @BeforeAll
    static void startStack() throws Exception {
        try {
            network = Network.newNetwork();
            postgres =
                    new PostgreSQLContainer<>("postgres:16-alpine")
                            .withNetwork(network)
                            .withNetworkAliases("postgres")
                            .withDatabaseName("omp")
                            .withUsername("omp_test")
                            .withPassword("omp_test")
                            .withInitScript("db/init-test-schemas.sql");
            kafka =
                    new KafkaContainer(
                                    org.testcontainers.utility.DockerImageName.parse(
                                            "confluentinc/cp-kafka:7.6.1"))
                            .withNetwork(network)
                            .withNetworkAliases("kafka")
                            .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                            .withListener(() -> "kafka:19092");
            redis =
                    new GenericContainer<>("redis:7.4-alpine")
                            .withNetwork(network)
                            .withNetworkAliases("redis")
                            .withExposedPorts(6379);
            postgres.start();
            kafka.start();
            redis.start();
            issuer = new TestJwtIssuer();
            subscriber =
                    new GenericContainer<>("eclipse-temurin:21-jre-jammy")
                            .withNetwork(network)
                            .withNetworkAliases("subscriber")
                            .withExposedPorts(8080)
                            .withCopyFileToContainer(
                                    MountableFile.forHostPath(
                                            Path.of(
                                                    "platform-tests/target/test-classes/com/example/omp/testsupport/ContainerSubscriber.class")),
                                    "/app/com/example/omp/testsupport/ContainerSubscriber.class")
                            .withCommand(
                                    "java",
                                    "-Xms32m",
                                    "-Xmx64m",
                                    "-cp",
                                    "/app",
                                    ContainerSubscriber.class.getName(),
                                    "8080")
                            .waitingFor(Wait.forHttp("/health").forStatusCode(200));
            subscriber.start();
            Testcontainers.exposeHostPorts(issuer.port());
            token = issuer.token(CUSTOMER_ID);
            notifications = service("notification-service", "notifications");
            notifications.start();
            payments = service("payment-service", "payments");
            payments.start();
            awaitWebhookConsumers();
            awaitAuthenticatedEndpoint(notifications, "/api/v1/webhook-subscriptions");
            awaitAuthenticatedEndpoint(payments, "/actuator/prometheus");
        } catch (Exception | Error e) {
            stopStack();
            throw e;
        }
    }

    private static void awaitWebhookConsumers() {
        Map<String, List<String>> groupTopics =
                Map.of(
                        "container-acceptance-outbound-webhook",
                                List.of(
                                        KafkaTopics.ORDERS_EVENTS_ORDER,
                                        KafkaTopics.PAYMENTS_EVENTS_PAYMENT,
                                        KafkaTopics.INVENTORY_EVENTS_RESERVATION),
                        "container-acceptance-webhook-delivery",
                                List.of(
                                        WebhookKafkaTopics.DELIVERIES,
                                        WebhookKafkaTopics.RETRY_1,
                                        WebhookKafkaTopics.RETRY_2,
                                        WebhookKafkaTopics.RETRY_3,
                                        WebhookKafkaTopics.RETRY_4,
                                        WebhookKafkaTopics.RETRY_5),
                        "container-acceptance-webhook-dlq", List.of(WebhookKafkaTopics.DLQ));
        try (var admin =
                AdminClient.create(
                        Map.of(
                                "bootstrap.servers",
                                kafka.getBootstrapServers(),
                                "default.api.timeout.ms",
                                5000,
                                "request.timeout.ms",
                                5000))) {
            await().atMost(Duration.ofSeconds(90))
                    .ignoreException(ExecutionException.class)
                    .untilAsserted(
                            () -> {
                                var descriptions =
                                        admin.describeConsumerGroups(groupTopics.keySet())
                                                .all()
                                                .get(10, TimeUnit.SECONDS);
                                var topics =
                                        admin.describeTopics(
                                                        groupTopics.values().stream()
                                                                .flatMap(Collection::stream)
                                                                .toList())
                                                .allTopicNames()
                                                .get(10, TimeUnit.SECONDS);
                                for (var entry : descriptions.entrySet()) {
                                    assertThat(entry.getValue().state())
                                            .as("Consumer group %s", entry.getKey())
                                            .isEqualTo(ConsumerGroupState.STABLE);
                                    assertThat(entry.getValue().members())
                                            .isNotEmpty()
                                            .allSatisfy(
                                                    member ->
                                                            assertThat(
                                                                            member.assignment()
                                                                                    .topicPartitions())
                                                                    .isNotEmpty());
                                    Set<TopicPartition> expected = new HashSet<>();
                                    for (String topic : groupTopics.get(entry.getKey())) {
                                        var partitions = topics.get(topic).partitions();
                                        if (entry.getKey().endsWith("-outbound-webhook")) {
                                            assertThat(partitions)
                                                    .as("Provisioned %s", topic)
                                                    .hasSize(3);
                                        }
                                        for (var partition : partitions) {
                                            expected.add(
                                                    new TopicPartition(
                                                            topic, partition.partition()));
                                        }
                                    }
                                    Set<TopicPartition> assigned = new HashSet<>();
                                    entry.getValue()
                                            .members()
                                            .forEach(
                                                    member ->
                                                            assigned.addAll(
                                                                    member.assignment()
                                                                            .topicPartitions()));
                                    assertThat(assigned)
                                            .as("All partitions assigned to %s", entry.getKey())
                                            .containsExactlyInAnyOrderElementsOf(expected);
                                }
                            });
            System.out.println("All webhook consumer partitions are assigned");
        }
    }

    private static void awaitAuthenticatedEndpoint(GenericContainer<?> service, String path) {
        // Health does not load the remote JWKS. Complete that cold-start work before
        // testing the steady-state rejection deadline with a deliberately bad signature.
        await().atMost(Duration.ofSeconds(90))
                .ignoreException(java.io.IOException.class)
                .untilAsserted(
                        () ->
                                assertThat(
                                                get(service, path, token, Duration.ofSeconds(30))
                                                        .statusCode())
                                        .isEqualTo(200));
    }

    private static GenericContainer<?> service(String module, String schema) throws Exception {
        Path jar;
        try (var files = Files.list(Path.of(module, "target"))) {
            var jars =
                    files.filter(file -> file.getFileName().toString().endsWith("-exec.jar"))
                            .toList();
            assertThat(jars)
                    .as("Package %s before running its container journey", module)
                    .hasSize(1);
            jar = jars.get(0);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.port", 8080);
        properties.put("management.server.port", 8080);
        properties.put("spring.profiles.active", "acceptance");
        properties.put(
                "spring.datasource.url",
                "jdbc:postgresql://postgres:5432/omp?currentSchema=" + schema + ",public");
        properties.put("spring.datasource.username", "omp_test");
        properties.put("spring.datasource.password", "omp_test");
        properties.put("spring.kafka.bootstrap-servers", "kafka:19092");
        properties.put("spring.kafka.consumer.group-id", "container-acceptance");
        properties.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        properties.put("spring.kafka.listener.concurrency", 1);
        properties.put("spring.data.redis.host", "redis");
        properties.put("spring.data.redis.port", 6379);
        properties.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", issuer.issuer());
        properties.put(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "http://host.testcontainers.internal:" + issuer.port() + "/jwks");
        properties.put("grpc.server.security.enabled", false);
        properties.put("grpc.server.port", 0);
        properties.put("webhook.kafka.replication-factor", 1);
        properties.put("webhook.kafka.event-concurrency", 1);
        properties.put("webhook.kafka.delivery-concurrency", 1);
        properties.put("webhook.kafka.dlq-concurrency", 1);
        properties.put("payment.provider", "noop");
        properties.put("logging.level.root", "WARN");
        properties.put("logging.level.com.example.omp.notification.webhook.matcher", "DEBUG");
        List<String> command =
                new ArrayList<>(List.of("java", "-Xms64m", "-Xmx512m", "-jar", "/app/service.jar"));
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        return new GenericContainer<>("eclipse-temurin:21-jre-jammy")
                .withNetwork(network)
                .withNetworkAliases(module)
                .withExposedPorts(8080)
                .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app/service.jar")
                .withCommand(command.toArray(String[]::new))
                .waitingFor(
                        Wait.forHttp("/actuator/health")
                                .forStatusCode(200)
                                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    @AfterAll
    static void stopStack() {
        if (payments != null && payments.isRunning()) {
            System.out.println("Payment container log:\n" + payments.getLogs());
        }
        if (notifications != null && notifications.isRunning()) {
            System.out.println("Notification container log:\n" + notifications.getLogs());
        }
        for (var container :
                new GenericContainer<?>[] {
                    payments, notifications, subscriber, redis, kafka, postgres
                }) {
            if (container != null) container.stop();
        }
        if (issuer != null) issuer.close();
        if (network != null) network.close();
    }

    @Test
    void payment_confirmed_reaches_subscriber_with_a_valid_signature() throws Exception {
        String target = "http://subscriber:8080/hook";
        var created =
                post(
                        notifications,
                        "/api/v1/webhook-subscriptions",
                        Map.of(
                                "name",
                                "container-test",
                                "targetUrl",
                                target,
                                "eventTypes",
                                List.of("payment.confirmed")),
                        token);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode subscription = MAPPER.readTree(created.body());
        String subId = subscription.path("id").asText();
        String secret = subscription.path("secret").asText();
        assertThat(subId).isNotBlank();
        assertThat(secret).isNotBlank();
        Map<String, Object> body =
                Map.of(
                        "orderId",
                        UUID.randomUUID().toString(),
                        "amountMinor",
                        1501,
                        "currency",
                        "USD",
                        "idempotencyKey",
                        "container-" + UUID.randomUUID());
        var charge = post(payments, "/api/v1/payments", body, token);
        assertThat(charge.statusCode()).as(charge.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(charge.body()).path("result").asText()).isEqualTo("confirmed");
        try {
            await().atMost(Duration.ofSeconds(40))
                    .untilAsserted(() -> assertThat(requests()).hasSize(1));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            try (var connection =
                            java.sql.DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var statement = connection.createStatement()) {
                for (String sql :
                        List.of(
                                "SELECT row_to_json(t)::text FROM (SELECT id,event_types,enabled,target_url FROM notifications.webhook_subscription) t",
                                "SELECT row_to_json(t)::text FROM (SELECT event_type,topic,status,payload FROM payments.outbox_event) t")) {
                    try (var rows = statement.executeQuery(sql)) {
                        while (rows.next())
                            System.out.println(
                                    "Pipeline database diagnostic: " + rows.getString(1));
                    }
                }
            }
            try (var admin =
                    AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                for (String group :
                        List.of(
                                "container-acceptance-outbound-webhook",
                                "container-acceptance-webhook-delivery")) {
                    System.out.println(
                            "Pipeline offsets "
                                    + group
                                    + ": "
                                    + admin.listConsumerGroupOffsets(group)
                                            .partitionsToOffsetAndMetadata()
                                            .get(10, TimeUnit.SECONDS));
                }
            }
            System.out.println(
                    "Delivery history on timeout: "
                            + get(
                                            notifications,
                                            "/api/v1/webhook-deliveries?subscriptionId=" + subId,
                                            token)
                                    .body());
            throw e;
        }
        CapturedRequest captured = requests().get(0);
        JsonNode payload = MAPPER.readTree(captured.body());
        assertThat(payload.path("eventType").asText()).isEqualTo("payment.confirmed");
        assertThat(payload.path("subscriptionId").asText()).isEqualTo(subId);
        String timestamp = captured.timestamp();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
                "sha256="
                        + HexFormat.of()
                                .formatHex(
                                        mac.doFinal(
                                                (timestamp + "." + captured.body())
                                                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(captured.signature()).isEqualTo(signature);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            var history =
                                    get(
                                            notifications,
                                            "/api/v1/webhook-deliveries?subscriptionId=" + subId,
                                            token);
                            assertThat(history.statusCode()).isEqualTo(200);
                            JsonNode attempts = MAPPER.readTree(history.body());
                            assertThat(attempts.isArray()).isTrue();
                            assertThat(attempts).hasSize(1);
                            assertThat(attempts.get(0).path("status").asText())
                                    .isEqualTo("SUCCESS");
                        });
        var duplicate = post(payments, "/api/v1/payments", body, token);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(duplicate.body()).path("paymentId"))
                .isEqualTo(MAPPER.readTree(charge.body()).path("paymentId"));
    }

    @Test
    void invalid_bearer_tokens_are_rejected_by_both_services() throws Exception {
        String[] parts = token.split("\\.");
        String badSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        String tampered = parts[0] + "." + parts[1] + "." + badSignature;
        for (String bearer : List.of("invalid-token", tampered)) {
            assertThat(get(notifications, "/api/v1/webhook-subscriptions", bearer).statusCode())
                    .isEqualTo(401);
            assertThat(get(payments, "/api/v1/payments/" + UUID.randomUUID(), bearer).statusCode())
                    .isEqualTo(401);
        }
    }

    private static String url(GenericContainer<?> service, String path) {
        return "http://" + service.getHost() + ":" + service.getMappedPort(8080) + path;
    }

    private record CapturedRequest(String body, String signature, String timestamp) {}

    private static List<CapturedRequest> requests() throws Exception {
        var response =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create(url(subscriber, "/requests")))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body()
                .lines()
                .filter(line -> !line.isEmpty())
                .map(
                        line -> {
                            String[] fields = line.split("\t", -1);
                            return new CapturedRequest(
                                    new String(
                                            Base64.getDecoder().decode(fields[0]),
                                            StandardCharsets.UTF_8),
                                    new String(
                                            Base64.getDecoder().decode(fields[1]),
                                            StandardCharsets.UTF_8),
                                    fields[2]);
                        })
                .toList();
    }

    private static HttpResponse<String> get(GenericContainer<?> service, String path, String bearer)
            throws Exception {
        return get(service, path, bearer, Duration.ofSeconds(10));
    }

    private static HttpResponse<String> get(
            GenericContainer<?> service, String path, String bearer, Duration timeout)
            throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(url(service, path)))
                        .header("Authorization", "Bearer " + bearer)
                        .timeout(timeout)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(
            GenericContainer<?> service, String path, Object body, String bearer) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(url(service, path)))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
