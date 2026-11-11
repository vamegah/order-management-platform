package com.example.omp.order.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.omp.order.clients.*;
import com.example.omp.order.clients.grpc.*;
import com.example.omp.order.clients.grpc.interceptor.AuthForwardingInterceptor;
import com.example.omp.order.domain.*;
import com.example.omp.order.events.OrderEventPublisher;
import com.example.omp.order.repository.OrderRepository;
import com.example.omp.order.service.*;
import com.example.omp.proto.payment.*;
import com.example.omp.shared.domain.Money;
import com.example.omp.shared.web.error.ProblemDetailsFactory;
import com.example.omp.shared.web.pagination.PageableResolver;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.micrometer.tracing.Tracer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP controller -> orchestrator -> real in-process payment RPC; local transactions are mocked.
 */
class PaidOrderTransitionContractTest {
    private Server server;
    private ManagedChannel channel;
    private MockMvc mvc;
    private com.example.omp.order.domain.Order order;
    private com.example.omp.proto.payment.Payment payment;
    private OrderTransactionService transactions;
    private OrderEventPublisher events;
    private OrderFulfillmentStore fulfillmentStore;
    private OrderFulfillmentQueue fulfillment;
    private OrderCleanupQueue cleanup;
    private Status remoteFailure;
    private Runnable beforeReply;
    private String forwardedToken;
    private String requestedPayment;
    private Long deadlineMillis;

    @BeforeEach
    void start() throws Exception {
        order =
                new com.example.omp.order.domain.Order(
                        UUID.randomUUID(),
                        OrderNumber.from(2026, 1),
                        UUID.randomUUID(),
                        "customer@example.com",
                        "USD",
                        new ShippingAddress("1 Main", null, "Chicago", "IL", "60601", "US"),
                        UUID.randomUUID());
        var line = new OrderLine(UUID.randomUUID(), "SKU", "Product", Money.usd("10"), 1);
        line.bindReservation(UUID.randomUUID(), "WH-1");
        order.addLine(line);
        order.place("customer");
        payment =
                com.example.omp.proto.payment.Payment.newBuilder()
                        .setId(order.getPaymentId().toString())
                        .setOrderId(order.getId().toString())
                        .setCustomerId(order.getCustomerId().toString())
                        .setAmount(
                                com.example.omp.proto.common.Money.newBuilder()
                                        .setAmount("10.0000")
                                        .setCurrency("USD"))
                        .setStatus(PaymentStatus.PAYMENT_STATUS_CONFIRMED)
                        .setProviderReference("pi-confirmed")
                        .build();
        String name = InProcessServerBuilder.generateName();
        server =
                InProcessServerBuilder.forName(name)
                        .directExecutor()
                        .intercept(
                                new ServerInterceptor() {
                                    @Override
                                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                            ServerCall<ReqT, RespT> call,
                                            Metadata headers,
                                            ServerCallHandler<ReqT, RespT> next) {
                                        forwardedToken =
                                                headers.get(
                                                        Metadata.Key.of(
                                                                "Authorization",
                                                                Metadata.ASCII_STRING_MARSHALLER));
                                        return next.startCall(call, headers);
                                    }
                                })
                        .addService(
                                new PaymentServiceGrpc.PaymentServiceImplBase() {
                                    @Override
                                    public void getPayment(
                                            GetPaymentRequest request,
                                            io.grpc.stub.StreamObserver<
                                                            com.example.omp.proto.payment.Payment>
                                                    observer) {
                                        requestedPayment = request.getPaymentId();
                                        if (Context.current().getDeadline() != null)
                                            deadlineMillis =
                                                    Context.current()
                                                            .getDeadline()
                                                            .timeRemaining(TimeUnit.MILLISECONDS);
                                        if (beforeReply != null) beforeReply.run();
                                        if (remoteFailure != null)
                                            observer.onError(remoteFailure.asRuntimeException());
                                        else {
                                            observer.onNext(payment);
                                            observer.onCompleted();
                                        }
                                    }
                                })
                        .build()
                        .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var client =
                new PaymentClient(
                        PaymentServiceGrpc.newBlockingStub(channel)
                                .withInterceptors(new AuthForwardingInterceptor()),
                        new GrpcExceptionMapper());
        transactions = mock(OrderTransactionService.class);
        events = mock(OrderEventPublisher.class);
        fulfillmentStore = mock(OrderFulfillmentStore.class);
        fulfillment = mock(OrderFulfillmentQueue.class);
        cleanup = mock(OrderCleanupQueue.class);
        when(transactions.findByIdFull(order.getId())).thenReturn(order);
        when(transactions.findAndApply(eq(order.getId()), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<com.example.omp.order.domain.Order> mutation =
                                    invocation.getArgument(1);
                            mutation.accept(order);
                            return order;
                        });
        var service =
                new OrderService(
                        mock(OrderRepository.class),
                        transactions,
                        mock(ProductClient.class),
                        mock(CustomerClient.class),
                        events,
                        mock(InventoryClient.class),
                        client,
                        cleanup,
                        mock(CheckoutJournal.class),
                        fulfillmentStore,
                        fulfillment,
                        mock(com.example.omp.order.service.OrderLifecycleCoordinator.class));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OrderController(service, mock(PageableResolver.class)))
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .setControllerAdvice(
                                new GlobalExceptionHandler(
                                        new ProblemDetailsFactory(mock(Tracer.class))))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("operator-token")
                                        .header("alg", "RS256")
                                        .subject("operator")
                                        .build()));
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    private String path(String operation) {
        return "/api/v1/orders/" + order.getId() + "/" + operation;
    }

    private void blocked(String operation, int expectedStatus) throws Exception {
        mvc.perform(post(path(operation)))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(expectedStatus));
        verifyNoInteractions(events);
    }

    @Test
    void matchingConfirmedPaymentAllowsConfirmationAndRelaysCallerWithDeadline() throws Exception {
        mvc.perform(post(path("confirm")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(requestedPayment).isEqualTo(order.getPaymentId().toString());
        assertThat(forwardedToken).isEqualTo("Bearer operator-token");
        assertThat(deadlineMillis).isBetween(1L, 5000L);
        verify(events).publishConfirmed(order, "operator", null);
        assertThat(order.getStatusHistory()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PAYMENT_STATUS_PENDING",
                "PAYMENT_STATUS_CANCELLED",
                "PAYMENT_STATUS_FAILED",
                "PAYMENT_STATUS_REFUNDED",
                "PAYMENT_STATUS_PARTIALLY_REFUNDED"
            })
    void unpaidOrRefundedPaymentCannotConfirm(String state) throws Exception {
        payment = payment.toBuilder().setStatus(PaymentStatus.valueOf(state)).build();
        blocked("confirm", 409);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getStatusHistory()).hasSize(2);
        verify(transactions, never()).findAndApply(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "order", "customer", "amount", "currency", "reference"})
    void mismatchedPaymentEvidenceCannotConfirm(String field) throws Exception {
        var response = payment.toBuilder();
        switch (field) {
            case "id" -> response.setId(UUID.randomUUID().toString());
            case "order" -> response.setOrderId(UUID.randomUUID().toString());
            case "customer" -> response.setCustomerId(UUID.randomUUID().toString());
            case "amount" ->
                    response.setAmount(
                            com.example.omp.proto.common.Money.newBuilder()
                                    .setAmount("9")
                                    .setCurrency("USD"));
            case "currency" ->
                    response.setAmount(
                            com.example.omp.proto.common.Money.newBuilder()
                                    .setAmount("10")
                                    .setCurrency("EUR"));
            case "reference" -> response.clearProviderReference();
        }
        payment = response.build();
        blocked("confirm", 409);
        verify(transactions, never()).findAndApply(any(), any());
    }

    @Test
    void concurrentLocalUpdatePreventsUsingStalePaymentVerification() throws Exception {
        beforeReply = () -> ReflectionTestUtils.setField(order, "version", 1L);
        blocked("confirm", 409);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void concurrentCancellationCannotBeOverwrittenByConfirmation() throws Exception {
        beforeReply = () -> order.cancel("customer", "Cancelled during verification");
        blocked("confirm", 409);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "UNAUTHENTICATED", "PERMISSION_DENIED"})
    void paymentReadFailuresAreRedactedAndDoNotMutateOrder(String code) throws Exception {
        remoteFailure =
                Status.fromCode(Status.Code.valueOf(code)).withDescription("provider-secret");
        var response =
                mvc.perform(post(path("confirm")))
                        .andExpect(status().isBadGateway())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).doesNotContain("provider-secret");
        verifyNoInteractions(events);
        verify(transactions, never()).findAndApply(any(), any());
    }

    @Test
    void missingPaymentIsAnOrderConflict() throws Exception {
        remoteFailure = Status.NOT_FOUND;
        blocked("confirm", 409);
    }

    @Test
    void malformedRemoteMoneyIsABadGateway() throws Exception {
        payment =
                payment.toBuilder()
                        .setAmount(
                                com.example.omp.proto.common.Money.newBuilder()
                                        .setAmount("not-money")
                                        .setCurrency("USD"))
                        .build();
        blocked("confirm", 502);
    }

    @Test
    void shippingRechecksPaymentAfterConfirmation() throws Exception {
        order.confirm("operator", "Earlier payment verification");
        payment = payment.toBuilder().setStatus(PaymentStatus.PAYMENT_STATUS_REFUNDED).build();
        blocked("ship", 409);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void shippingAcceptsMatchingConfirmedPayment() throws Exception {
        order.confirm("operator", "Earlier payment verification");
        mvc.perform(post(path("ship")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/orders/" + order.getId()))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value("FULFILLING"));
        verify(fulfillmentStore).enqueue(order, "operator", "Shipment requested");
        verify(fulfillment).retryOrder(order.getId(), 100);
        verifyNoInteractions(events);
    }

    @Test
    void successfulFastPathReturnsShippedAndRetryDoesNotCreateAnotherIntent() throws Exception {
        order.confirm("operator", "Paid");
        when(fulfillment.retryOrder(order.getId(), 100))
                .thenAnswer(
                        invocation -> {
                            order.ship("operator", "Shipment requested");
                            return 1;
                        });
        mvc.perform(post(path("ship")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(post(path("ship"))).andExpect(status().isOk());
        verify(fulfillmentStore).enqueue(order, "operator", "Shipment requested");
        verify(fulfillment).retryOrder(order.getId(), 100);
    }

    @Test
    void durablePendingShipmentCanRetryAfterRestartAndCancellationRemainsPending()
            throws Exception {
        order.confirm("operator", "Paid");
        order.requestShipment("operator", "Shipment requested before restart");
        when(fulfillment.retryOrder(order.getId(), 100))
                .thenThrow(new IllegalStateException("downstream-secret"));
        var response =
                mvc.perform(post(path("ship")))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("FULFILLING"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).doesNotContain("downstream-secret");
        mvc.perform(post(path("cancel")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value("CANCELLING"));
        verify(cleanup).trackCancellation(eq(order.getId()), eq("operator"), isNull());
        verifyNoInteractions(fulfillmentStore, events);
    }

    @Test
    void missingPaymentLinkDoesNotCallPaymentService() throws Exception {
        ReflectionTestUtils.setField(order, "paymentId", null);
        blocked("confirm", 409);
        assertThat(requestedPayment).isNull();
    }

    @Test
    void malformedRemoteIdIsABadGateway() throws Exception {
        payment = payment.toBuilder().setId("not-a-uuid").build();
        blocked("confirm", 502);
        verify(transactions, never()).findAndApply(any(), any());
    }

    @Test
    void zeroRemoteAmountIsABadGateway() throws Exception {
        payment =
                payment.toBuilder()
                        .setAmount(
                                com.example.omp.proto.common.Money.newBuilder()
                                        .setAmount("0")
                                        .setCurrency("USD"))
                        .build();
        blocked("confirm", 502);
    }

    @Test
    void invalidInitialStateDoesNotReadPayment() throws Exception {
        order.cancel("customer", "Cancelled before verification");
        blocked("confirm", 409);
        assertThat(requestedPayment).isNull();
        verify(transactions, never()).findAndApply(any(), any());
    }
}
