package com.example.omp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.payment.repository.PaymentRepository;
import com.example.omp.payment.service.PaymentApplicationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFlowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("omp")
                    .withUsername("payments_svc")
                    .withPassword("test")
                    .withUrlParam("currentSchema", "payments,public");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("payment.provider", () -> "noop");
        registry.add("grpc.server.security.enabled", () -> false);
        registry.add("grpc.server.port", () -> 0);
        registry.add("server.port", () -> 0);
        registry.add("management.server.port", () -> 0);
    }

    @Autowired PaymentApplicationService paymentService;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void charge_succeeds_and_persists_confirmed_payment() {
        var outcome =
                paymentService.charge(
                        new PaymentApplicationService.ChargeCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                1501L,
                                "USD",
                                "idem-" + UUID.randomUUID()));

        assertThat(outcome.result()).isEqualTo("confirmed");

        var payment = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        assertThat(payment.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(payment.getProviderReference()).startsWith("noop_pi_");
    }

    @Test
    void charge_is_idempotent_on_key() {
        String key = "idem-" + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        var first =
                paymentService.charge(
                        new PaymentApplicationService.ChargeCommand(
                                orderId, customerId, 1200L, "USD", key));
        var second =
                paymentService.charge(
                        new PaymentApplicationService.ChargeCommand(
                                orderId, customerId, 1200L, "USD", key));

        assertThat(first.paymentId()).isEqualTo(second.paymentId());
    }

    @Test
    void declined_charge_persists_failed_payment() {
        // Noop provider declines amounts divisible by 100.
        var outcome =
                paymentService.charge(
                        new PaymentApplicationService.ChargeCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                1000L,
                                "USD",
                                "idem-" + UUID.randomUUID()));

        assertThat(outcome.result()).isEqualTo("declined");

        var payment = paymentRepository.findById(outcome.paymentId()).orElseThrow();
        assertThat(payment.getStatus().name()).isEqualTo("FAILED");
        assertThat(payment.getFailureCode()).isEqualTo("card_declined");
    }
}
