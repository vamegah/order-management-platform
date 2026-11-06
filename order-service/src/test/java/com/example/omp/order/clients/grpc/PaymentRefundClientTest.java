package com.example.omp.order.clients.grpc;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.proto.payment.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentRefundClientTest {
    private Server server;
    private ManagedChannel channel;
    private PaymentClient client;
    private final UUID paymentId = UUID.randomUUID(), refundId = UUID.randomUUID();
    private final List<RefundPaymentRequest> requests = new ArrayList<>();
    private String mode = "valid";

    @BeforeEach
    void start() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    @Override
                                    public void refundPayment(
                                            RefundPaymentRequest request,
                                            StreamObserver<Refund> observer) {
                                        assertThat(Context.current().getDeadline()).isNotNull();
                                        assertThat(
                                                        Context.current()
                                                                .getDeadline()
                                                                .timeRemaining(
                                                                        TimeUnit.MILLISECONDS))
                                                .isBetween(1L, 5000L);
                                        requests.add(request);
                                        if (mode.equals("error")) {
                                            observer.onError(
                                                    Status.UNAVAILABLE
                                                            .withDescription(
                                                                    "sensitive provider payload")
                                                            .asRuntimeException());
                                            return;
                                        }
                                        observer.onNext(
                                                Refund.newBuilder()
                                                        .setId(
                                                                mode.equals("malformed")
                                                                        ? "bad-id"
                                                                        : refundId.toString())
                                                        .setPaymentId(paymentId.toString())
                                                        .setStatus(
                                                                mode.equals("pending")
                                                                        ? RefundStatus
                                                                                .REFUND_STATUS_PENDING
                                                                        : RefundStatus
                                                                                .REFUND_STATUS_COMPLETED)
                                                        .build());
                                        observer.onCompleted();
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client =
                new PaymentClient(
                        PaymentServiceGrpc.newBlockingStub(channel), new GrpcExceptionMapper());
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "pending"})
    void remainingRefundOmitsAmountAndReusesKeyAcrossCalls(String outcome) {
        mode = outcome;
        var first = client.refundRemaining(paymentId, "stable-key");
        assertThat(client.refundRemaining(paymentId, "stable-key")).isEqualTo(first);
        assertThat(first.id()).isEqualTo(refundId);
        assertThat(first.paymentId()).isEqualTo(paymentId);
        assertThat(first.status())
                .isEqualTo(
                        outcome.equals("pending")
                                ? "REFUND_STATUS_PENDING"
                                : "REFUND_STATUS_COMPLETED");
        assertThat(requests)
                .hasSize(2)
                .allSatisfy(
                        request -> {
                            assertThat(request.getPaymentId()).isEqualTo(paymentId.toString());
                            assertThat(request.getIdempotencyKey()).isEqualTo("stable-key");
                            assertThat(request.hasAmount()).isFalse();
                            assertThat(request.getReason()).isEqualTo("Order cleanup");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "error"})
    void invalidOrFailedReplyIsRedactedForTheOrderBoundary(String failure) {
        mode = failure;
        assertThatThrownBy(() -> client.refundRemaining(paymentId, "stable-key"))
                .isInstanceOf(com.example.omp.order.error.DownstreamServiceException.class)
                .hasMessageNotContaining("sensitive");
    }
}
