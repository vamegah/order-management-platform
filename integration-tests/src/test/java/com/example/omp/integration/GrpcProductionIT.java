package com.example.omp.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.example.omp.integration.support.*;
import com.example.omp.proto.inventory.*;
import com.example.omp.proto.payment.*;
import com.google.protobuf.Message;
import io.grpc.*;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every published RPC is exercised over real production-profile mTLS listeners. */
@Tag("grpc-e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcProductionIT {
    private static final String SKU = "PHONE-BUDGET-3";
    private static GrpcAcceptanceDeployment deployment;

    @BeforeAll
    static void isolation() throws Exception {
        deployment = new GrpcAcceptanceDeployment();
    }

    private static final Map<String, Set<String>> COVERAGE =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void covered(String method, String scenario) {
        COVERAGE.computeIfAbsent(method, key -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(scenario);
    }

    static void positive(MethodDescriptor<?, ?>... methods) {
        for (var method : methods) covered(method.getFullMethodName(), "positive");
    }

    @AfterAll
    static void close() throws Exception {
        try {
            var expected =
                    Set.of(
                            "positive",
                            "error",
                            "missing-identity",
                            "invalid-identity",
                            "deadline",
                            "cancellation");
            for (Rpc rpc : methods().toList())
                assertThat(COVERAGE.get(rpc.method().getFullMethodName()))
                        .as(rpc.toString())
                        .containsAll(expected);
            Path report = Path.of("integration-tests/target/grpc-acceptance-matrix.json");
            Files.createDirectories(report.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(report.toFile(), COVERAGE);
        } finally {
            GrpcClientFactory.shutdown();
        }
    }

    record Rpc(MethodDescriptor<Message, Message> method, Message request, boolean inventory) {
        @Override
        public String toString() {
            return method.getBareMethodName();
        }

        Channel channel(String token) {
            return inventory
                    ? GrpcClientFactory.inventoryChannel(token)
                    : GrpcClientFactory.paymentChannel(token);
        }
    }

    @SuppressWarnings("unchecked")
    static Stream<Rpc> methods() {
        var methods = new ArrayList<Rpc>();
        for (var service :
                List.of(
                        InventoryServiceGrpc.getServiceDescriptor(),
                        PaymentServiceGrpc.getServiceDescriptor())) {
            for (var descriptor : service.getMethods()) {
                var method =
                        (MethodDescriptor<Message, Message>) (MethodDescriptor<?, ?>) descriptor;
                var prototype =
                        ((io.grpc.protobuf.ProtoMethodDescriptorSupplier)
                                        method.getSchemaDescriptor())
                                .getMethodDescriptor()
                                .getInputType();
                Message request = com.google.protobuf.DynamicMessage.getDefaultInstance(prototype);
                String name = method.getBareMethodName();
                if ("StreamStockUpdates".equals(name))
                    request = StreamStockUpdatesRequest.newBuilder().addSkus("").build();
                if ("StreamPaymentEvents".equals(name))
                    request = StreamPaymentEventsRequest.newBuilder().setOrderId("bad").build();
                if ("BulkReserve".equals(name))
                    request = BulkReserveItem.newBuilder().setOrderId("bad").build();
                methods.add(new Rpc(method, request, service.getName().contains("inventory")));
            }
        }
        return methods.stream();
    }

    static GrpcProbe<Message, Message> invoke(Rpc rpc, String token, CallOptions options) {
        var response = new GrpcProbe<Message, Message>();
        var call = rpc.channel(token).newCall(rpc.method(), options);
        switch (rpc.method().getType()) {
            case UNARY -> ClientCalls.asyncUnaryCall(call, rpc.request(), response);
            case SERVER_STREAMING ->
                    ClientCalls.asyncServerStreamingCall(call, rpc.request(), response);
            case CLIENT_STREAMING -> {
                var input = ClientCalls.asyncClientStreamingCall(call, response);
                input.onNext(rpc.request());
                input.onCompleted();
            }
            case BIDI_STREAMING -> {
                var input = ClientCalls.asyncBidiStreamingCall(call, response);
                input.onNext(rpc.request());
                input.onCompleted();
            }
            default -> throw new AssertionError("Unsupported published RPC type");
        }
        return response;
    }

    @Order(1)
    @ParameterizedTest(name = "{0}: bearer authentication")
    @MethodSource("methods")
    void everyRpcRejectsMissingAndInvalidBearerIdentity(Rpc rpc) throws Exception {
        for (String token : new String[] {null, "invalid.jwt.signature"}) {
            var response =
                    invoke(rpc, token, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS));
            assertThat(response.finish().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
            covered(
                    rpc.method().getFullMethodName(),
                    token == null ? "missing-identity" : "invalid-identity");
        }
    }

    @Order(2)
    @ParameterizedTest(name = "{0}: stable domain error")
    @MethodSource("methods")
    void everyRpcReturnsDocumentedErrors(Rpc rpc) throws Exception {
        var status =
                invoke(
                                rpc,
                                AuthSupport.adminToken(),
                                CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS))
                        .finish();
        assertThat(status.getCode())
                .isIn(
                        Status.Code.INVALID_ARGUMENT,
                        Status.Code.NOT_FOUND,
                        Status.Code.PERMISSION_DENIED);
        assertThat(status.getDescription())
                .doesNotContain("Exception", "SELECT ", "password", "jdbc:");
        covered(rpc.method().getFullMethodName(), "error");
    }

    @Order(3)
    @ParameterizedTest(name = "{0}: cancellation")
    @MethodSource("methods")
    void everyRpcCanBeCancelledWithoutWaitingForAResponse(Rpc rpc) throws Exception {
        var closed = new CompletableFuture<Status>();
        String service = rpc.inventory() ? "inventory-service" : "payment-service";
        deployment.pause(service);
        try {
            var call =
                    rpc.channel(AuthSupport.adminToken())
                            .newCall(
                                    rpc.method(),
                                    CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS));
            call.start(
                    new ClientCall.Listener<>() {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            closed.complete(status);
                        }
                    },
                    new Metadata());
            call.request(1);
            call.sendMessage(rpc.request());
            call.halfClose();
            call.cancel("Acceptance cancellation during transport stall", null);
            assertThat(closed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(Status.Code.CANCELLED);
            covered(rpc.method().getFullMethodName(), "cancellation");
        } finally {
            deployment.unpause(service);
        }
    }

    @Test
    @Order(4)
    void everyRpcDeadlineExpiresDuringARealTransportStallAndChannelsRecover() throws Exception {
        stock();
        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .getPayment(GetPaymentRequest.newBuilder().setPaymentId(payment().getId()).build());
        deployment.pause("inventory-service");
        try {
            deployment.pause("payment-service");
            try {
                for (Rpc rpc : methods().toList()) {
                    var response =
                            invoke(
                                    rpc,
                                    AuthSupport.adminToken(),
                                    CallOptions.DEFAULT.withDeadlineAfter(
                                            400, TimeUnit.MILLISECONDS));
                    assertThat(response.finish().getCode())
                            .as(rpc.toString())
                            .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
                    covered(rpc.method().getFullMethodName(), "deadline");
                }
            } finally {
                deployment.unpause("payment-service");
            }
        } finally {
            deployment.unpause("inventory-service");
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(stock().getSku()).isEqualTo(SKU));
    }

    @Test
    @Order(5)
    void unaryInventoryLifecyclePreservesStockAcrossCommitCompensationAndRelease()
            throws Exception {
        var before = stock();
        var client = GrpcClientFactory.inventoryClient(AuthSupport.adminToken());
        var recovery = GrpcClientFactory.inventoryClient(deployment.recoveryToken());
        String order = UUID.randomUUID().toString();
        assertThat(client.checkAvailability(query()).getAvailable()).isTrue();
        var held = client.reserveStock(reserve(order));
        assertThat(client.reserveStock(reserve(order)).getId()).isEqualTo(held.getId());
        assertThat(
                        client.getReservation(
                                        GetReservationRequest.newBuilder()
                                                .setReservationId(held.getId())
                                                .build())
                                .getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_ACTIVE);
        assertThat(
                        recovery.findReservationsByOrder(
                                        FindReservationsByOrderRequest.newBuilder()
                                                .setOrderId(order)
                                                .build())
                                .getReservationsList())
                .extracting(Reservation::getId)
                .containsExactly(held.getId());
        assertThat(
                        client.commitReservation(
                                        CommitReservationRequest.newBuilder()
                                                .setReservationId(held.getId())
                                                .build())
                                .getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_COMMITTED);
        assertThat(
                        client.compensateOrder(
                                        CompensateOrderRequest.newBuilder()
                                                .setOrderId(order)
                                                .build())
                                .getReservations(0)
                                .getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RESTOCKED);
        var another = client.reserveStock(reserve(UUID.randomUUID().toString()));
        assertThat(
                        client.releaseReservation(
                                        ReleaseReservationRequest.newBuilder()
                                                .setReservationId(another.getId())
                                                .build())
                                .getStatus())
                .isEqualTo(ReservationStatus.RESERVATION_STATUS_RELEASED);
        assertThat(stock().getQuantityOnHand()).isEqualTo(before.getQuantityOnHand());
        assertThat(stock().getQuantityReserved()).isEqualTo(before.getQuantityReserved());
        positive(
                InventoryServiceGrpc.getGetStockLevelMethod(),
                InventoryServiceGrpc.getCheckAvailabilityMethod(),
                InventoryServiceGrpc.getReserveStockMethod(),
                InventoryServiceGrpc.getReleaseReservationMethod(),
                InventoryServiceGrpc.getCommitReservationMethod(),
                InventoryServiceGrpc.getGetReservationMethod(),
                InventoryServiceGrpc.getFindReservationsByOrderMethod(),
                InventoryServiceGrpc.getCompensateOrderMethod());
    }

    @Test
    @Order(6)
    void clientStreamingSupportsPartialFailureReplayAndRejectsOversizedStreamsWithoutWrites()
            throws Exception {
        String order = UUID.randomUUID().toString();
        var response = new GrpcProbe<BulkReserveItem, BulkReserveResponse>();
        var input =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .bulkReserve(response);
        input.onNext(item(order, SKU));
        input.onNext(item(order, "UNKNOWN-SKU"));
        input.onCompleted();
        var result = response.next();
        assertThat(response.finish().isOk()).isTrue();
        assertThat(result.getReservationsCount()).isEqualTo(1);
        assertThat(result.getFailuresCount()).isEqualTo(1);
        positive(InventoryServiceGrpc.getBulkReserveMethod());
        assertThat(
                        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                                .reserveStock(reserve(order))
                                .getId())
                .isEqualTo(result.getReservations(0).getId());
        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .compensateOrder(CompensateOrderRequest.newBuilder().setOrderId(order).build());
        String rejected = UUID.randomUUID().toString();
        var oversized = new GrpcProbe<BulkReserveItem, BulkReserveResponse>();
        var stream =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .bulkReserve(oversized);
        for (int i = 0; i < 101; i++) stream.onNext(item(rejected, SKU));
        stream.onCompleted();
        assertThat(oversized.finish().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(
                        GrpcClientFactory.inventoryClient(deployment.recoveryToken())
                                .findReservationsByOrder(
                                        FindReservationsByOrderRequest.newBuilder()
                                                .setOrderId(rejected)
                                                .build())
                                .getReservationsCount())
                .isZero();
    }

    @Test
    @Order(7)
    void cancellingClientStreamingBeforeHalfCloseLeavesNoReservations() throws Exception {
        String order = UUID.randomUUID().toString();
        var response = new GrpcProbe<BulkReserveItem, BulkReserveResponse>();
        var input =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .bulkReserve(response);
        input.onNext(item(order, SKU));
        response.cancel();
        assertThat(response.finish().getCode()).isEqualTo(Status.Code.CANCELLED);
        assertThat(
                        GrpcClientFactory.inventoryClient(deployment.recoveryToken())
                                .findReservationsByOrder(
                                        FindReservationsByOrderRequest.newBuilder()
                                                .setOrderId(order)
                                                .build())
                                .getReservationsCount())
                .isZero();
    }

    @Test
    @Order(8)
    void bidirectionalQueriesPreserveOrderingRespectDemandAndDrainOnHalfClose() throws Exception {
        var response = new GrpcProbe<CheckAvailabilityRequest, CheckAvailabilityResponse>(true);
        var input =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(20, TimeUnit.SECONDS)
                        .checkAvailabilityStream(response);
        for (int i = 0; i < 32; i++)
            input.onNext(
                    query().toBuilder()
                            .setRequestedQuantity(i % 2 == 0 ? 1 : Integer.MAX_VALUE)
                            .build());
        input.onCompleted();
        assertThat(response.next().getAvailable()).isTrue();
        assertThat(response.buffered()).isZero();
        assertThat(response.done()).isFalse();
        for (int i = 1; i < 32; i++) {
            response.request(1);
            assertThat(response.next().getAvailable()).isEqualTo(i % 2 == 0);
        }
        assertThat(response.finish().isOk()).isTrue();
        positive(InventoryServiceGrpc.getCheckAvailabilityStreamMethod());
        var cancelled = new GrpcProbe<CheckAvailabilityRequest, CheckAvailabilityResponse>();
        var session =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .checkAvailabilityStream(cancelled);
        session.onNext(query());
        cancelled.next();
        cancelled.cancel();
        assertThat(cancelled.finish().getCode()).isEqualTo(Status.Code.CANCELLED);
    }

    @Test
    @Order(9)
    void paymentUnaryLifecycleCoversDiscoveryDecisionsRefundAndCancellation() throws Exception {
        var client = GrpcClientFactory.paymentClient(AuthSupport.adminToken());
        var payment = payment();
        assertThat(
                        client.getPayment(
                                        GetPaymentRequest.newBuilder()
                                                .setPaymentId(payment.getId())
                                                .build())
                                .getOrderId())
                .isEqualTo(payment.getOrderId());
        assertThat(
                        GrpcClientFactory.paymentClient(deployment.recoveryToken())
                                .findPaymentsByOrder(
                                        FindPaymentsByOrderRequest.newBuilder()
                                                .setOrderId(payment.getOrderId())
                                                .build())
                                .getPaymentsList())
                .extracting(Payment::getId)
                .containsExactly(payment.getId());
        var confirm =
                ConfirmPaymentRequest.newBuilder()
                        .setPaymentId(payment.getId())
                        .setProviderReference("grpc-" + payment.getId())
                        .build();
        assertThat(client.confirmPayment(confirm).getStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_CONFIRMED);
        assertThat(client.confirmPayment(confirm).getId()).isEqualTo(payment.getId());
        var decision =
                FulfillmentDecisionRequest.newBuilder()
                        .setPaymentId(payment.getId())
                        .setOrderId(payment.getOrderId())
                        .setAction(FulfillmentAction.FULFILLMENT_ACTION_ALLOW_REFUND)
                        .build();
        assertThat(
                        client.decideFulfillment(
                                        decision.toBuilder()
                                                .setAction(
                                                        FulfillmentAction.FULFILLMENT_ACTION_CANCEL)
                                                .build())
                                .getState())
                .isEqualTo("CANCELLING");
        assertThat(client.decideFulfillment(decision).getState()).isEqualTo("REFUNDABLE");
        var refund =
                RefundPaymentRequest.newBuilder()
                        .setPaymentId(payment.getId())
                        .setIdempotencyKey(UUID.randomUUID().toString())
                        .setReason("gRPC acceptance")
                        .build();
        var refunded = client.refundPayment(refund);
        assertThat(client.refundPayment(refund).getId()).isEqualTo(refunded.getId());
        assertThat(refunded.getStatus()).isEqualTo(RefundStatus.REFUND_STATUS_COMPLETED);
        positive(
                PaymentServiceGrpc.getCreatePaymentIntentMethod(),
                PaymentServiceGrpc.getGetPaymentMethod(),
                PaymentServiceGrpc.getFindPaymentsByOrderMethod(),
                PaymentServiceGrpc.getConfirmPaymentMethod(),
                PaymentServiceGrpc.getRefundPaymentMethod(),
                PaymentServiceGrpc.getDecideFulfillmentMethod());
        var pending = payment();
        assertThat(
                        client.cancelPayment(
                                        CancelPaymentRequest.newBuilder()
                                                .setPaymentId(pending.getId())
                                                .build())
                                .getStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
        positive(PaymentServiceGrpc.getCancelPaymentMethod());
    }

    @Test
    @Order(10)
    void serverStreamsDeliverCommittedChangesRespectDemandAndCancel() throws Exception {
        var stocks = stockStream();
        stocks.next();
        var client = GrpcClientFactory.inventoryClient(AuthSupport.adminToken());
        var held = client.reserveStock(reserve(UUID.randomUUID().toString()));
        stocks.request(1);
        assertThat(stocks.next().getReason())
                .isEqualTo(StockUpdateReason.STOCK_UPDATE_REASON_RESERVED);
        client.releaseReservation(
                ReleaseReservationRequest.newBuilder().setReservationId(held.getId()).build());
        stocks.request(1);
        assertThat(stocks.next().getReason())
                .isEqualTo(StockUpdateReason.STOCK_UPDATE_REASON_RELEASED);
        stocks.cancel();
        assertThat(stocks.finish().getCode()).isEqualTo(Status.Code.CANCELLED);
        var created = payment();
        var events = paymentStream(created.getOrderId());
        // A real response establishes registration before the tested transition.
        awaitPaymentRegistration(events, created);
        events.request(1);
        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .cancelPayment(
                        CancelPaymentRequest.newBuilder().setPaymentId(created.getId()).build());
        assertThat(nextTransition(events, created.getId()).getToStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
        events.cancel();
        assertThat(events.finish().getCode()).isEqualTo(Status.Code.CANCELLED);
        positive(
                InventoryServiceGrpc.getStreamStockUpdatesMethod(),
                PaymentServiceGrpc.getStreamPaymentEventsMethod());
    }

    @Test
    @Order(11)
    void streamsReconnectAndReconcileAfterRealContainerReplacement() throws Exception {
        var stock = stockStream();
        stock.next();
        var bidi = new GrpcProbe<CheckAvailabilityRequest, CheckAvailabilityResponse>();
        var queries =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(5, TimeUnit.MINUTES)
                        .checkAvailabilityStream(bidi);
        queries.onNext(query());
        bidi.next();
        String inventory = deployment.container("inventory-service");
        deployment.replace("inventory-service");
        assertThat(deployment.container("inventory-service")).isNotEqualTo(inventory);
        assertThat(stock.finish().isOk()).isFalse();
        assertThat(bidi.finish().isOk()).isFalse();
        GrpcClientFactory.shutdown();
        var missed =
                GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                        .reserveStock(reserve(UUID.randomUUID().toString()));
        var resumed = stockStream();
        var snapshot = resumed.next();
        assertThat(snapshot.getQuantityAvailable()).isEqualTo(stock().getQuantityAvailable());
        assertThat(snapshot.getQuantityReserved()).isGreaterThan(0);
        var resumedQueries = new GrpcProbe<CheckAvailabilityRequest, CheckAvailabilityResponse>();
        var newSession =
                InventoryServiceGrpc.newStub(
                                GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                        .withDeadlineAfter(10, TimeUnit.SECONDS)
                        .checkAvailabilityStream(resumedQueries);
        newSession.onNext(query());
        newSession.onCompleted();
        assertThat(resumedQueries.next().getAvailableQuantity())
                .isEqualTo(stock().getQuantityAvailable());
        assertThat(resumedQueries.finish().isOk()).isTrue();
        var held =
                GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                        .reserveStock(reserve(UUID.randomUUID().toString()));
        resumed.request(1);
        assertThat(resumed.next().getReason())
                .isEqualTo(StockUpdateReason.STOCK_UPDATE_REASON_RESERVED);
        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .releaseReservation(
                        ReleaseReservationRequest.newBuilder()
                                .setReservationId(held.getId())
                                .build());
        resumed.cancel();
        GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .releaseReservation(
                        ReleaseReservationRequest.newBuilder()
                                .setReservationId(missed.getId())
                                .build());
        var payment = payment();
        var events = paymentStream(payment.getOrderId());
        awaitPaymentRegistration(events, payment);
        String payments = deployment.container("payment-service");
        deployment.replace("payment-service");
        assertThat(deployment.container("payment-service")).isNotEqualTo(payments);
        assertThat(events.finish().isOk()).isFalse();
        GrpcClientFactory.shutdown();
        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .cancelPayment(
                        CancelPaymentRequest.newBuilder().setPaymentId(payment.getId()).build());
        assertThat(
                        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                                .getPayment(
                                        GetPaymentRequest.newBuilder()
                                                .setPaymentId(payment.getId())
                                                .build())
                                .getStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
        var pending = payment(payment.getOrderId());
        var resubscribed = paymentStream(payment.getOrderId());
        awaitPaymentRegistration(resubscribed, payment);
        resubscribed.request(1);
        GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .cancelPayment(
                        CancelPaymentRequest.newBuilder().setPaymentId(pending.getId()).build());
        assertThat(nextTransition(resubscribed, pending.getId()).getToStatus())
                .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
        resubscribed.cancel();
    }

    static Stream<String> rejectedTransports() {
        return Stream.of("inventory-service", "payment-service")
                .flatMap(
                        service ->
                                Stream.of(
                                                "plaintext",
                                                "missing-client",
                                                "untrusted-client",
                                                "untrusted-server",
                                                "wrong-hostname",
                                                "expired-client",
                                                "wrong-usage")
                                        .map(mode -> service + ":" + mode));
    }

    @Order(12)
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTransports")
    void rejectsUnauthenticatedOrIncorrectTlsPeers(String scenario) throws Exception {
        String[] parts = scenario.split(":");
        String service = parts[0], mode = parts[1];
        int port =
                service.equals("inventory-service")
                        ? Integer.parseInt(
                                System.getenv().getOrDefault("INVENTORY_GRPC_PORT", "9091"))
                        : Integer.parseInt(
                                System.getenv().getOrDefault("PAYMENT_GRPC_PORT", "9093"));
        String host =
                System.getenv()
                        .getOrDefault(
                                service.equals("inventory-service")
                                        ? "INVENTORY_GRPC_HOST"
                                        : "PAYMENT_GRPC_HOST",
                                "127.0.0.1");
        var builder =
                NettyChannelBuilder.forAddress(host, port)
                        .overrideAuthority(
                                mode.equals("wrong-hostname") ? "wrong.invalid" : service);
        if (mode.equals("plaintext")) builder.usePlaintext();
        else {
            var tls =
                    GrpcSslContexts.forClient()
                            .trustManager(
                                    deployment
                                            .issuer()
                                            .resolve(
                                                    mode.equals("untrusted-server")
                                                            ? "untrusted/tls.crt"
                                                            : "trust/ca.crt")
                                            .toFile());
            if (!mode.equals("missing-client")) {
                String folder =
                        switch (mode) {
                            case "untrusted-client" -> "untrusted";
                            case "retired-client" -> "retired-client";
                            case "expired-client" -> "expired";
                            case "wrong-usage" -> "wrong-usage";
                            default -> "order-service";
                        };
                Path identity = deployment.issuer().resolve(folder);
                tls.keyManager(
                        identity.resolve("tls.crt").toFile(), identity.resolve("tls.key").toFile());
            }
            builder.sslContext(tls.build());
        }
        var channel = builder.build();
        try {
            var token = new Metadata();
            token.put(
                    Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + AuthSupport.adminToken());
            var caller =
                    ClientInterceptors.intercept(
                            channel, MetadataUtils.newAttachHeadersInterceptor(token));
            assertThatThrownBy(
                            () -> {
                                if (service.equals("inventory-service"))
                                    InventoryServiceGrpc.newBlockingStub(caller)
                                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                                            .getStockLevel(
                                                    GetStockLevelRequest.newBuilder()
                                                            .setSku(SKU)
                                                            .setWarehouseId("WH-1")
                                                            .build());
                                else
                                    PaymentServiceGrpc.newBlockingStub(caller)
                                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                                            .getPayment(
                                                    GetPaymentRequest.newBuilder()
                                                            .setPaymentId(
                                                                    UUID.randomUUID().toString())
                                                            .build());
                            })
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            error ->
                                    assertThat(error.getStatus().getCode())
                                            .isEqualTo(Status.Code.UNAVAILABLE));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(13)
    void automaticReloadAcceptsRenewedPeersWithoutFailedCallsRestartingOrClosingStreams()
            throws Exception {
        var before = new HashMap<String, String>();
        for (String service : List.of("order-service", "inventory-service", "payment-service"))
            before.put(service, deployment.startedAt(service));
        var stocks = stockStream();
        stocks.next();
        var payment = payment();
        var payments = paymentStream(payment.getOrderId());
        awaitPaymentRegistration(payments, payment);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        var traffic = Executors.newSingleThreadScheduledExecutor();
        traffic.scheduleWithFixedDelay(
                () -> {
                    try {
                        stock();
                        completed.incrementAndGet();
                    } catch (Throwable failure) {
                        errors.add(failure);
                    }
                },
                0,
                50,
                TimeUnit.MILLISECONDS);
        try {
            deployment.renew();
            for (String service : List.of("inventory-service", "payment-service")) {
                var expected =
                        ((java.security.cert.X509Certificate)
                                        java.security.cert.CertificateFactory.getInstance("X.509")
                                                .generateCertificate(
                                                        Files.newInputStream(
                                                                deployment
                                                                        .issuer()
                                                                        .resolve(
                                                                                service
                                                                                        + "/tls.crt"))))
                                .getSerialNumber();
                await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(serverSerial(service)).isEqualTo(expected));
            }
            var held =
                    GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                            .reserveStock(reserve(UUID.randomUUID().toString()));
            stocks.request(1);
            assertThat(stocks.next().getReason())
                    .isEqualTo(StockUpdateReason.STOCK_UPDATE_REASON_RESERVED);
            GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                    .releaseReservation(
                            ReleaseReservationRequest.newBuilder()
                                    .setReservationId(held.getId())
                                    .build());
            payments.request(1);
            GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                    .cancelPayment(
                            CancelPaymentRequest.newBuilder()
                                    .setPaymentId(payment.getId())
                                    .build());
            assertThat(nextTransition(payments, payment.getId()).getToStatus())
                    .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
            assertThat(stocks.done()).isFalse();
            assertThat(payments.done()).isFalse();
            assertThat(completed.get()).isGreaterThan(5);
            assertThat(errors).isEmpty();
            for (var entry : before.entrySet())
                assertThat(deployment.startedAt(entry.getKey())).isEqualTo(entry.getValue());
            // The real order-service channels must still perform checkout with the rotated
            // projection.
            var order = OrderJourney.create();
            order.transition("cancel", false).then().statusCode(200);
        } finally {
            traffic.shutdownNow();
            stocks.cancel();
            payments.cancel();
        }
    }

    @Test
    @Order(14)
    void overlappingCaRotationRetiresOldTrustWithoutFailedCallsOrLostStreams() throws Exception {
        var stocks = stockStream();
        stocks.next();
        var pending = payment();
        var payments = paymentStream(pending.getOrderId());
        awaitPaymentRegistration(payments, pending);
        var before = new HashMap<String, String>();
        for (String service : List.of("order-service", "inventory-service", "payment-service"))
            before.put(service, deployment.startedAt(service));
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var traffic = Executors.newSingleThreadScheduledExecutor();
        traffic.scheduleWithFixedDelay(
                () -> {
                    try {
                        stock();
                        calls.incrementAndGet();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                },
                0,
                50,
                TimeUnit.MILLISECONDS);
        try {
            for (String phase : List.of("prepare", "activate", "retire")) {
                var marker = java.time.Instant.now();
                deployment.rotateCa(phase);
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(
                                () -> {
                                    for (String service : before.keySet())
                                        assertThat(deployment.reloadedAfter(service, marker))
                                                .as(service + " reloaded " + phase)
                                                .isTrue();
                                });
                assertThat(serverSerial("inventory-service")).isNotNull();
                assertThat(serverSerial("payment-service")).isNotNull();
            }
            try (var input =
                    Files.newInputStream(deployment.issuer().resolve("retired-client/tls.crt"))) {
                ((java.security.cert.X509Certificate)
                                java.security.cert.CertificateFactory.getInstance("X.509")
                                        .generateCertificate(input))
                        .checkValidity();
            }
            for (String service : List.of("inventory-service", "payment-service"))
                rejectsUnauthenticatedOrIncorrectTlsPeers(service + ":retired-client");
            var held =
                    GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                            .reserveStock(reserve(UUID.randomUUID().toString()));
            stocks.request(1);
            assertThat(stocks.next().getReason())
                    .isEqualTo(StockUpdateReason.STOCK_UPDATE_REASON_RESERVED);
            GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                    .releaseReservation(
                            ReleaseReservationRequest.newBuilder()
                                    .setReservationId(held.getId())
                                    .build());
            payments.request(1);
            GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                    .cancelPayment(
                            CancelPaymentRequest.newBuilder()
                                    .setPaymentId(pending.getId())
                                    .build());
            assertThat(nextTransition(payments, pending.getId()).getToStatus())
                    .isEqualTo(PaymentStatus.PAYMENT_STATUS_CANCELLED);
            assertThat(stocks.done()).isFalse();
            assertThat(payments.done()).isFalse();
            assertThat(calls.get()).isPositive();
            assertThat(failures).isEmpty();
            for (var entry : before.entrySet())
                assertThat(deployment.startedAt(entry.getKey())).isEqualTo(entry.getValue());
            var order = OrderJourney.create();
            order.transition("cancel", false).then().statusCode(200);
        } finally {
            traffic.shutdownNow();
            stocks.cancel();
            payments.cancel();
        }
    }

    @Test
    @Order(15)
    void validUnprivilegedCallersCannotInvokeRecoveryOrAdministrativeRpcs() {
        String order = UUID.randomUUID().toString();
        String token = AuthSupport.aliceToken();
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.inventoryClient(token)
                                        .findReservationsByOrder(
                                                FindReservationsByOrderRequest.newBuilder()
                                                        .setOrderId(order)
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.inventoryClient(token)
                                        .compensateOrder(
                                                CompensateOrderRequest.newBuilder()
                                                        .setOrderId(order)
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.paymentClient(token)
                                        .findPaymentsByOrder(
                                                FindPaymentsByOrderRequest.newBuilder()
                                                        .setOrderId(order)
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED));
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.paymentClient(token)
                                        .decideFulfillment(
                                                FulfillmentDecisionRequest.newBuilder()
                                                        .setOrderId(order)
                                                        .setPaymentId(UUID.randomUUID().toString())
                                                        .setAction(
                                                                FulfillmentAction
                                                                        .FULFILLMENT_ACTION_BEGIN)
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e ->
                                assertThat(e.getStatus().getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    static java.math.BigInteger serverSerial(String service) throws Exception {
        String host =
                System.getenv()
                        .getOrDefault(
                                service.equals("inventory-service")
                                        ? "INVENTORY_GRPC_HOST"
                                        : "PAYMENT_GRPC_HOST",
                                "127.0.0.1");
        int port =
                Integer.parseInt(
                        System.getenv()
                                .getOrDefault(
                                        service.equals("inventory-service")
                                                ? "INVENTORY_GRPC_PORT"
                                                : "PAYMENT_GRPC_PORT",
                                        service.equals("inventory-service") ? "9091" : "9093"));
        Path identity = deployment.issuer().resolve("order-service");
        var tls =
                GrpcSslContexts.forClient()
                        .trustManager(deployment.issuer().resolve("trust/ca.crt").toFile())
                        .keyManager(
                                identity.resolve("tls.crt").toFile(),
                                identity.resolve("tls.key").toFile())
                        .build();
        var serial = new AtomicReference<java.math.BigInteger>();
        var channel =
                NettyChannelBuilder.forAddress(host, port)
                        .overrideAuthority(service)
                        .sslContext(tls)
                        .intercept(
                                new ClientInterceptor() {
                                    @Override
                                    public <Q, S> ClientCall<Q, S> interceptCall(
                                            MethodDescriptor<Q, S> method,
                                            CallOptions options,
                                            Channel next) {
                                        var call = next.newCall(method, options);
                                        return new ForwardingClientCall
                                                .SimpleForwardingClientCall<>(call) {
                                            @Override
                                            public void start(
                                                    Listener<S> listener, Metadata headers) {
                                                super.start(
                                                        new ForwardingClientCallListener
                                                                .SimpleForwardingClientCallListener<>(
                                                                listener) {
                                                            @Override
                                                            public void onHeaders(
                                                                    Metadata response) {
                                                                try {
                                                                    var session =
                                                                            call.getAttributes()
                                                                                    .get(
                                                                                            Grpc
                                                                                                    .TRANSPORT_ATTR_SSL_SESSION);
                                                                    serial.set(
                                                                            ((java.security.cert
                                                                                                    .X509Certificate)
                                                                                            session.getPeerCertificates()[
                                                                                                    0])
                                                                                    .getSerialNumber());
                                                                } catch (
                                                                        javax.net.ssl
                                                                                        .SSLPeerUnverifiedException
                                                                                failure) {
                                                                    throw new AssertionError(
                                                                            failure);
                                                                }
                                                                super.onHeaders(response);
                                                            }
                                                        },
                                                        headers);
                                            }
                                        };
                                    }
                                })
                        .build();
        try {
            var headers = new Metadata();
            headers.put(
                    Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + AuthSupport.adminToken());
            var caller =
                    ClientInterceptors.intercept(
                            channel, MetadataUtils.newAttachHeadersInterceptor(headers));
            if (service.equals("inventory-service"))
                InventoryServiceGrpc.newBlockingStub(caller)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getStockLevel(
                                GetStockLevelRequest.newBuilder()
                                        .setSku(SKU)
                                        .setWarehouseId("WH-1")
                                        .build());
            else {
                var created = payment();
                PaymentServiceGrpc.newBlockingStub(caller)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getPayment(
                                GetPaymentRequest.newBuilder()
                                        .setPaymentId(created.getId())
                                        .build());
            }
            return serial.get();
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    static com.example.omp.proto.inventory.StockLevel stock() {
        return GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                .getStockLevel(
                        GetStockLevelRequest.newBuilder()
                                .setSku(SKU)
                                .setWarehouseId("WH-1")
                                .build());
    }

    static CheckAvailabilityRequest query() {
        return CheckAvailabilityRequest.newBuilder()
                .setSku(SKU)
                .setWarehouseId("WH-1")
                .setRequestedQuantity(1)
                .build();
    }

    static ReserveStockRequest reserve(String order) {
        return ReserveStockRequest.newBuilder()
                .setSku(SKU)
                .setWarehouseId("WH-1")
                .setQuantity(1)
                .setOrderId(order)
                .build();
    }

    static BulkReserveItem item(String order, String sku) {
        return BulkReserveItem.newBuilder()
                .setSku(sku)
                .setWarehouseId("WH-1")
                .setQuantity(1)
                .setOrderId(order)
                .build();
    }

    static Payment payment() {
        return payment(UUID.randomUUID().toString());
    }

    static Payment payment(String order) {
        return GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                .createPaymentIntent(
                        CreatePaymentIntentRequest.newBuilder()
                                .setOrderId(order)
                                .setCustomerId(OrderJourney.CUSTOMER_ID)
                                .setIdempotencyKey(UUID.randomUUID().toString())
                                .setAmount(
                                        com.example.omp.proto.common.Money.newBuilder()
                                                .setCurrency("USD")
                                                .setAmount("10.00"))
                                .build());
    }

    static GrpcProbe<StreamStockUpdatesRequest, StockUpdate> stockStream() {
        var probe = new GrpcProbe<StreamStockUpdatesRequest, StockUpdate>(true);
        InventoryServiceGrpc.newStub(GrpcClientFactory.inventoryChannel(AuthSupport.adminToken()))
                .withDeadlineAfter(5, TimeUnit.MINUTES)
                .streamStockUpdates(
                        StreamStockUpdatesRequest.newBuilder()
                                .addSkus(SKU)
                                .setWarehouseId("WH-1")
                                .build(),
                        probe);
        return probe;
    }

    static GrpcProbe<StreamPaymentEventsRequest, PaymentEvent> paymentStream(String order) {
        var probe = new GrpcProbe<StreamPaymentEventsRequest, PaymentEvent>(true);
        PaymentServiceGrpc.newStub(GrpcClientFactory.paymentChannel(AuthSupport.adminToken()))
                .withDeadlineAfter(5, TimeUnit.MINUTES)
                .streamPaymentEvents(
                        StreamPaymentEventsRequest.newBuilder().setOrderId(order).build(), probe);
        return probe;
    }

    static PaymentEvent nextTransition(
            GrpcProbe<StreamPaymentEventsRequest, PaymentEvent> events, String paymentId)
            throws Exception {
        for (int i = 0; i < 100; i++) {
            var event = events.next();
            if (event.getPaymentId().equals(paymentId)) return event;
            events.request(1);
        }
        throw new AssertionError("Expected payment transition was not delivered");
    }

    static void awaitPaymentRegistration(
            GrpcProbe<StreamPaymentEventsRequest, PaymentEvent> events, Payment payment)
            throws Exception {
        // Mutations for the same order produce a repeatable registration barrier; no arbitrary
        // sleep.
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .until(
                        () -> {
                            GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                                    .createPaymentIntent(
                                            CreatePaymentIntentRequest.newBuilder()
                                                    .setOrderId(payment.getOrderId())
                                                    .setCustomerId(OrderJourney.CUSTOMER_ID)
                                                    .setIdempotencyKey(UUID.randomUUID().toString())
                                                    .setAmount(
                                                            com.example.omp.proto.common.Money
                                                                    .newBuilder()
                                                                    .setCurrency("USD")
                                                                    .setAmount("1.00"))
                                                    .build());
                            return events.buffered() > 0;
                        });
        assertThat(events.next().getOrderId()).isEqualTo(payment.getOrderId());
    }
}
