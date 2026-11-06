package com.example.omp.order.clients.grpc.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.order.service.OrderFulfillmentJob;
import com.example.omp.order.service.OrderFulfillmentQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrderFulfillmentJobTest {
    @TempDir Path directory;

    @Test
    void rotatesCredentialsEachTickAndRestoresTheOuterIdentityOnFailures() throws Exception {
        var path = directory.resolve("token");
        Files.writeString(path, "first.payload.signature");
        var queue = mock(OrderFulfillmentQueue.class);
        List<String> identities = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            identities.add(ServiceTokenScope.current());
                            if (identities.size() == 2)
                                throw new IllegalStateException("private downstream details");
                            return null;
                        })
                .when(queue)
                .retryDue();
        var metrics = new SimpleMeterRegistry();
        var job = new OrderFulfillmentJob(queue, metrics, path);
        try (var outer = ServiceTokenScope.open("outer.payload.signature")) {
            job.run();
            assertThat(ServiceTokenScope.current()).isEqualTo("outer.payload.signature");
            Files.writeString(path, "rotated.payload.signature");
            job.run();
            assertThat(ServiceTokenScope.current()).isEqualTo("outer.payload.signature");
        }
        assertThat(ServiceTokenScope.current()).isNull();
        assertThat(identities)
                .containsExactly("first.payload.signature", "rotated.payload.signature");
        assertThat(metrics.get("order.fulfillment.worker.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void missingMalformedAndOversizedFilesNeverStartWork() throws Exception {
        var path = directory.resolve("token");
        var queue = mock(OrderFulfillmentQueue.class);
        var metrics = new SimpleMeterRegistry();
        var job = new OrderFulfillmentJob(queue, metrics, path);
        job.run();
        Files.writeString(path, "bad\nAuthorization: injected");
        job.run();
        Files.writeString(path, "a".repeat(16385));
        job.run();
        verifyNoInteractions(queue);
        assertThat(ServiceTokenScope.current()).isNull();
        assertThat(metrics.get("order.fulfillment.worker.failures").counter().count()).isEqualTo(3);
    }
}
