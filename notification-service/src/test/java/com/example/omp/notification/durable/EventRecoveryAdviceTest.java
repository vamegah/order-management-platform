package com.example.omp.notification.durable;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class EventRecoveryAdviceTest {
    final EventRecoveryAdvice advice = new EventRecoveryAdvice();

    @Test
    void invalidRequestsUseAStableRedactedProblem() {
        var problem = advice.invalid();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getType().toString()).isEqualTo("urn:problem:event-recovery-400");
    }

    @Test
    void missingAndConcurrentReplayPreserveStatusWithoutInternalDetails() {
        for (var status : java.util.List.of(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT)) {
            var response =
                    advice.status(new ResponseStatusException(status, "secret database detail"));
            assertThat(response.getStatusCode()).isEqualTo(status);
            assertThat(response.getBody().getDetail()).doesNotContain("secret");
        }
    }
}
