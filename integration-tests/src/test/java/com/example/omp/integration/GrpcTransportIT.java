package com.example.omp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.omp.integration.support.AuthSupport;
import com.example.omp.integration.support.GrpcClientFactory;
import com.example.omp.proto.inventory.GetStockLevelRequest;
import com.example.omp.proto.payment.GetPaymentRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verify the deployed transports independently of checkout orchestration. */
@Tag("e2e")
class GrpcTransportIT {
    @AfterAll
    static void closeChannels() throws InterruptedException {
        GrpcClientFactory.shutdown();
    }

    @Test
    void authenticatedInventoryReadUsesTheConfiguredTransport() {
        var stock =
                GrpcClientFactory.inventoryClient(AuthSupport.adminToken())
                        .getStockLevel(
                                GetStockLevelRequest.newBuilder()
                                        .setSku("PHONE-BUDGET-3")
                                        .setWarehouseId("WH-1")
                                        .build());
        assertThat(stock.getSku()).isEqualTo("PHONE-BUDGET-3");
        assertThat(stock.getWarehouseId()).isEqualTo("WH-1");
    }

    @Test
    void authenticatedPaymentLookupReturnsAStableNotFoundError() {
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.paymentClient(AuthSupport.adminToken())
                                        .getPayment(
                                                GetPaymentRequest.newBuilder()
                                                        .setPaymentId(UUID.randomUUID().toString())
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.NOT_FOUND));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = "invalid.jwt.signature")
    void inventoryRejectsMissingOrInvalidBearerIdentity(String token) {
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.inventoryClient(token)
                                        .getStockLevel(
                                                GetStockLevelRequest.newBuilder()
                                                        .setSku("PHONE-BUDGET-3")
                                                        .setWarehouseId("WH-1")
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = "invalid.jwt.signature")
    void paymentRejectsMissingOrInvalidBearerIdentity(String token) {
        assertThatThrownBy(
                        () ->
                                GrpcClientFactory.paymentClient(token)
                                        .getPayment(
                                                GetPaymentRequest.newBuilder()
                                                        .setPaymentId(UUID.randomUUID().toString())
                                                        .build()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        failure ->
                                assertThat(failure.getStatus().getCode())
                                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
