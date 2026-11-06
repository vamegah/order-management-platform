package com.example.omp.order.clients.grpc;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.proto.inventory.InventoryServiceGrpc;
import com.example.omp.proto.payment.PaymentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.*;
import java.util.UUID;
import org.junit.jupiter.api.*;

/** Prevent fake-service data from violating the real clients' wire contract. */
class DownstreamFixtureContractTest {
    private Server server;
    private ManagedChannel channel;
    private InventoryClient inventory;
    private PaymentClient payments;

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(new FakeInventoryService())
                        .addService(new FakePaymentService())
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        inventory =
                new InventoryClient(
                        InventoryServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
        payments =
                new PaymentClient(
                        PaymentServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void inventoryFixtureHasUuidIdsAndIdempotentCommits() {
        UUID order = UUID.randomUUID();
        var reservation = inventory.reserve(order, "SKU", "WH-1", 2);
        assertThat(inventory.get(reservation.id()).id()).isEqualTo(reservation.id());
        assertThat(inventory.commit(reservation.id().toString()).status())
                .isEqualTo("RESERVATION_STATUS_COMMITTED");
        assertThat(inventory.commit(reservation.id().toString()).status())
                .isEqualTo("RESERVATION_STATUS_COMMITTED");
        assertThat(inventory.getStockLevel("SKU", "WH-1").quantityOnHand()).isEqualTo(98);
        assertThatThrownBy(() -> inventory.release(reservation.id().toString()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void paymentFixtureCanBeReadThroughTheRealUuidClient() {
        UUID order = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        var payment = payments.createIntent(order, customer, "10.0000", "USD", "fixture-key");
        assertThat(payment.id()).isNotNull();
        assertThat(payments.get(payment.id()).orderId()).isEqualTo(order);
        assertThat(payments.get(payment.id()).customerId()).isEqualTo(customer);
    }
}
