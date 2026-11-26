package com.example.omp.shared.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GrpcLegacyObservationFilterTest {
    @Test
    void replacesOnlyLegacyObservationFactoriesAndPreservesTransportAuthenticationAndMetrics() {
        String prefix = "net.devh.boot.grpc.";
        String[] candidates = {
            prefix + "client.autoconfigure.GrpcClientMicrometerTraceAutoConfiguration",
            prefix + "server.autoconfigure.GrpcServerMicrometerTraceAutoConfiguration",
            prefix + "client.autoconfigure.GrpcClientAutoConfiguration",
            prefix + "server.autoconfigure.GrpcServerAutoConfiguration",
            prefix + "client.autoconfigure.GrpcClientMetricAutoConfiguration",
            prefix + "server.autoconfigure.GrpcServerMetricAutoConfiguration",
            "com.example.omp.shared.grpc.GrpcAuthenticationAutoConfiguration",
            null
        };
        assertThat(new GrpcLegacyObservationFilter().match(candidates, null))
                .containsExactly(false, true, true, true, true, true, true, true);
    }
}
