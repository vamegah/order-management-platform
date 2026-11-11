package com.example.omp.order.clients.grpc;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.order.error.DownstreamServiceException;
import com.example.omp.proto.common.Money;
import com.example.omp.proto.inventory.*;
import com.example.omp.proto.payment.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LifecycleRpcContractTest {
    private Server server;
    private ManagedChannel channel;
    private PaymentClient payments;
    private InventoryClient inventory;
    private final UUID orderId = UUID.randomUUID(), paymentId = UUID.randomUUID();
    private FulfillmentDecision decision;
    private FindReservationsByOrderResponse compensated;
    private boolean fail;
    private long remaining;

    @BeforeEach
    void start() throws Exception {
        var payment =
                Payment.newBuilder()
                        .setId(paymentId.toString())
                        .setOrderId(orderId.toString())
                        .setCustomerId(UUID.randomUUID().toString())
                        .setAmount(Money.newBuilder().setAmount("10").setCurrency("USD"))
                        .setStatus(PaymentStatus.PAYMENT_STATUS_CANCELLED)
                        .setFulfillmentState("REFUNDABLE")
                        .build();
        decision =
                FulfillmentDecision.newBuilder().setPayment(payment).setState("REFUNDABLE").build();
        compensated =
                FindReservationsByOrderResponse.newBuilder()
                        .addReservations(
                                Reservation.newBuilder()
                                        .setId(UUID.randomUUID().toString())
                                        .setOrderId(orderId.toString())
                                        .setSku("SKU")
                                        .setWarehouseId("WH-1")
                                        .setQuantity(1)
                                        .setStatus(ReservationStatus.RESERVATION_STATUS_RESTOCKED))
                        .build();
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    @Override
                                    public void decideFulfillment(
                                            FulfillmentDecisionRequest request,
                                            StreamObserver<FulfillmentDecision> observer) {
                                        assertThat(request.getPaymentId())
                                                .isEqualTo(paymentId.toString());
                                        assertThat(request.getOrderId())
                                                .isEqualTo(orderId.toString());
                                        remaining =
                                                Context.current()
                                                        .getDeadline()
                                                        .timeRemaining(TimeUnit.MILLISECONDS);
                                        if (fail) {
                                            observer.onError(
                                                    Status.UNAVAILABLE
                                                            .withDescription(
                                                                    "private-provider-secret")
                                                            .asRuntimeException());
                                            return;
                                        }
                                        observer.onNext(decision);
                                        observer.onCompleted();
                                    }
                                })
                        .addService(
                                new InventoryServiceGrpc.InventoryServiceImplBase() {
                                    @Override
                                    public void compensateOrder(
                                            CompensateOrderRequest request,
                                            StreamObserver<FindReservationsByOrderResponse>
                                                    observer) {
                                        assertThat(request.getOrderId())
                                                .isEqualTo(orderId.toString());
                                        remaining =
                                                Context.current()
                                                        .getDeadline()
                                                        .timeRemaining(TimeUnit.MILLISECONDS);
                                        if (fail) {
                                            observer.onError(
                                                    Status.UNAVAILABLE
                                                            .withDescription("private-stock-secret")
                                                            .asRuntimeException());
                                            return;
                                        }
                                        observer.onNext(compensated);
                                        observer.onCompleted();
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        payments =
                new PaymentClient(
                        PaymentServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
        inventory =
                new InventoryClient(
                        InventoryServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
    }

    @AfterEach
    void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private Object decide() {
        return payments.decide(
                paymentId, orderId, FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND);
    }

    @Test
    void requestsCarryCanonicalIdentitiesAndFiveSecondDeadlines() {
        assertThat(
                        payments.decide(
                                        paymentId,
                                        orderId,
                                        FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND)
                                .fulfillmentState())
                .isEqualTo("REFUNDABLE");
        assertThat(remaining).isBetween(1L, 5000L);
        assertThat(inventory.compensateOrder(orderId))
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.orderId()).isEqualTo(orderId);
                            assertThat(r.status()).isEqualTo("RESERVATION_STATUS_RESTOCKED");
                        });
        assertThat(remaining).isBetween(1L, 5000L);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "foreign-payment",
                "foreign-order",
                "malformed-customer",
                "missing-payment",
                "state-mismatch",
                "unknown-state"
            })
    void malformedOrUncorrelatedDecisionsAreRedactedGatewayFailures(String fault) {
        var p = decision.getPayment().toBuilder();
        switch (fault) {
            case "foreign-payment" -> p.setId(UUID.randomUUID().toString());
            case "foreign-order" -> p.setOrderId(UUID.randomUUID().toString());
            case "malformed-customer" -> p.setCustomerId("private-invalid-identity");
            case "missing-payment" -> p.clear();
            case "state-mismatch" -> p.setFulfillmentState("CANCELLING");
            case "unknown-state" -> {
                p.setFulfillmentState("UNRECOGNIZED");
                decision = decision.toBuilder().setState("UNRECOGNIZED").build();
            }
            default -> throw new AssertionError(fault);
        }
        decision = decision.toBuilder().setPayment(p).build();
        assertThatThrownBy(this::decide)
                .isInstanceOf(DownstreamServiceException.class)
                .hasMessageNotContaining("private-invalid-identity");
    }

    @Test
    void transportAndMalformedCompensationFailuresNeverExposeRemoteDescriptions() {
        fail = true;
        assertThatThrownBy(this::decide)
                .isInstanceOf(DownstreamServiceException.class)
                .hasMessageNotContaining("private-provider-secret");
        assertThatThrownBy(() -> inventory.compensateOrder(orderId))
                .isInstanceOf(DownstreamServiceException.class)
                .hasMessageNotContaining("private-stock-secret");
        fail = false;
        compensated =
                compensated.toBuilder()
                        .setReservations(
                                0,
                                compensated.getReservations(0).toBuilder()
                                        .setId("private-invalid-id"))
                        .build();
        assertThatThrownBy(() -> inventory.compensateOrder(orderId))
                .isInstanceOf(DownstreamServiceException.class)
                .hasMessageNotContaining("private-invalid-id");
    }
}
