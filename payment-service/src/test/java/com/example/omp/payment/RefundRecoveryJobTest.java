package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.payment.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

class RefundRecoveryJobTest {
    @Configuration
    @Import(RefundRecoveryJob.class)
    static class Beans {
        @Bean
        RefundRecoveryService service() {
            return mock(RefundRecoveryService.class);
        }

        @Bean
        io.micrometer.core.instrument.MeterRegistry metrics() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void workerIsDisabledUnlessExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(Beans.class)
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(RefundRecoveryJob.class);
                            verifyNoInteractions(context.getBean(RefundRecoveryService.class));
                        });
    }

    @Test
    void failedTickDoesNotPreventLaterTick() {
        var service = mock(RefundRecoveryService.class);
        var metrics = new SimpleMeterRegistry();
        when(service.retryDue())
                .thenThrow(new IllegalStateException("private provider text"))
                .thenReturn(1);
        try (var job = new RefundRecoveryJob(service, metrics, 30000)) {
            job.tick();
            job.tick();
            verify(service, times(2)).retryDue();
            assertThat(metrics.get("payment.refund.recovery.worker.failures").counter().count())
                    .isEqualTo(1);
        }
    }

    @Test
    void dedicatedWorkerStartsOnceAndShutdownInterruptsItsWork() throws Exception {
        var service = mock(RefundRecoveryService.class);
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var threadName = new AtomicReference<String>();
        when(service.retryDue())
                .thenAnswer(
                        call -> {
                            threadName.set(Thread.currentThread().getName());
                            entered.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                stopped.countDown();
                            }
                            return 0;
                        });
        try (var job = new RefundRecoveryJob(service, new SimpleMeterRegistry(), 30000)) {
            job.start();
            job.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("refund-recovery-");
            job.close();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            verify(service).retryDue();
        }
    }

    @Test
    void interruptedCallerDoesNotStartWorkAndRetainsFlag() {
        var service = mock(RefundRecoveryService.class);
        try (var job = new RefundRecoveryJob(service, new SimpleMeterRegistry(), 30000)) {
            Thread.currentThread().interrupt();
            try {
                job.tick();
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            verifyNoInteractions(service);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 999, 3600001})
    void invalidIntervalFailsBeforeThreadCreation(long interval) {
        assertThatThrownBy(
                        () ->
                                new RefundRecoveryJob(
                                        mock(RefundRecoveryService.class),
                                        new SimpleMeterRegistry(),
                                        interval))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noopLookupCannotManufactureMissingLedgerEntries() {
        var provider =
                new com.example.omp.payment.provider.noop.NoopPaymentProvider(
                        new com.fasterxml.jackson.databind.ObjectMapper());
        var request =
                new com.example.omp.payment.provider.RefundRequest(
                        java.util.UUID.randomUUID(), "pi_dev", 400, "USD", "key", null);
        assertThat(provider.lookupRefund(request, "missing").status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
        var created = provider.refund(request);
        assertThat(provider.lookupRefund(request, created.providerReference())).isEqualTo(created);
        assertThat(provider.lookupRefund(request, "wrong").status())
                .isEqualTo(com.example.omp.payment.provider.ProviderRefundResult.Status.ERROR);
    }
}
