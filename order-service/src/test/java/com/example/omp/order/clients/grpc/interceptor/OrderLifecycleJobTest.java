package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrderLifecycleJobTest {
    @TempDir Path directory;

    @Test
    void renewedProjectionIsUsedForEveryTickAndOuterIdentitySurvivesFailure() throws Exception {
        var token = directory.resolve("token");
        var lifecycle = mock(OrderLifecycleCoordinator.class);
        var fulfillment = mock(OrderFulfillmentQueue.class);
        var metrics = new SimpleMeterRegistry();
        var identities = new ArrayList<String>();
        doAnswer(
                        call -> {
                            identities.add(ServiceTokenScope.current());
                            return null;
                        })
                .when(lifecycle)
                .retryDue();
        doThrow(new IllegalStateException("private downstream details"))
                .when(fulfillment)
                .retryDue();
        var job = new OrderLifecycleJob(lifecycle, fulfillment, metrics, token);
        try (var outer = ServiceTokenScope.open("outer.payload.signature")) {
            Files.writeString(token, "first.payload.signature");
            job.run();
            assertThat(ServiceTokenScope.current()).isEqualTo("outer.payload.signature");
            Files.writeString(token, "renewed.payload.signature");
            job.run();
            assertThat(ServiceTokenScope.current()).isEqualTo("outer.payload.signature");
        }
        assertThat(identities)
                .containsExactly("first.payload.signature", "renewed.payload.signature");
        assertThat(ServiceTokenScope.current()).isNull();
        assertThat(metrics.get("order.lifecycle.worker.failures").counter().count()).isEqualTo(2);
    }

    @Test
    void missingMalformedAndOversizedIdentityNeverAdmitOrProcessWork() throws Exception {
        var token = directory.resolve("token");
        var lifecycle = mock(OrderLifecycleCoordinator.class);
        var fulfillment = mock(OrderFulfillmentQueue.class);
        var metrics = new SimpleMeterRegistry();
        var job = new OrderLifecycleJob(lifecycle, fulfillment, metrics, token);
        job.run();
        Files.writeString(token, "bad\nAuthorization: injected");
        job.run();
        Files.writeString(token, "a".repeat(16385));
        job.run();
        verifyNoInteractions(lifecycle, fulfillment);
        assertThat(metrics.get("order.lifecycle.worker.failures").counter().count()).isEqualTo(3);
        assertThat(ServiceTokenScope.current()).isNull();
    }
}
