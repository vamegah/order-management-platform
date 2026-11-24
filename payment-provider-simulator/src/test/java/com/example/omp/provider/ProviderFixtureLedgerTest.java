package com.example.omp.provider;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ProviderFixtureLedgerTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    ProviderFixtureLedger ledger() throws Exception {
        var env = new MockEnvironment();
        env.setActiveProfiles("test");
        return new ProviderFixtureLedger(mapper, env, directory.toString());
    }

    Map<String, String> payment(String method) {
        return new HashMap<>(
                Map.of(
                        "amount",
                        "1000",
                        "currency",
                        "usd",
                        "payment_method",
                        method,
                        "metadata[payment_id]",
                        UUID.randomUUID().toString(),
                        "metadata[order_id]",
                        UUID.randomUUID().toString(),
                        "metadata[customer_id]",
                        UUID.randomUUID().toString()));
    }

    @Test
    void restartPreservesIdempotencyAndDoesNotRepeatCapture() throws Exception {
        var request = payment("pm_success");
        var first = ledger().charge(request, "same-key");
        var restored = ledger();
        assertThat(restored.charge(request, "same-key").toString()).isEqualTo(first.toString());
        assertThat(restored.stats().get("payments")).isEqualTo(1L);
        request.put("amount", "2000");
        assertThatThrownBy(() -> restored.charge(request, "same-key"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void actionMustCompleteBeforeMoneyIsCaptured() throws Exception {
        var ledger = ledger();
        var intent = ledger.charge(payment("pm_action"), "action");
        String id = intent.path("id").asText();
        assertThat(intent.path("status").asText()).isEqualTo("requires_action");
        assertThat(ledger.complete(id, true).path("status").asText()).isEqualTo("succeeded");
        assertThat(ledger().get(id).path("status").asText()).isEqualTo("succeeded");
        assertThatThrownBy(() -> ledger.complete(id, true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void concurrentPartialRefundsCannotOverspendAndSurviveRestart() throws Exception {
        var ledger = ledger();
        String id = ledger.charge(payment("pm_success"), "charge").path("id").asText();
        var request =
                new HashMap<>(
                        Map.of(
                                "payment_intent",
                                id,
                                "amount",
                                "600",
                                "metadata[payment_id]",
                                UUID.randomUUID().toString(),
                                "metadata[refund_id]",
                                UUID.randomUUID().toString()));
        var refund = ledger.refund(request, "refund");
        assertThat(refund.path("status").asText()).isEqualTo("pending");
        var restored = ledger();
        assertThat(restored.refund(request, "refund").toString()).isEqualTo(refund.toString());
        assertThatThrownBy(() -> restored.refund(request, "second-key"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(restored.stats().get("refunds")).isEqualTo(1L);
    }

    @Test
    void callbackIdsAndSuppressionPersistAndDuplicateControlsAreBounded() throws Exception {
        var ledger = ledger();
        ledger.configure(mapper.readTree("{\"dropCallbacks\":true,\"delayMs\":0}"));
        ledger.charge(payment("pm_success"), "charge");
        assertThat(ledger.due()).isEmpty();
        var restored = ledger();
        assertThat(restored.due()).isEmpty();
        restored.configure(mapper.readTree("{\"dropCallbacks\":false,\"duplicateCallbacks\":2}"));
        var callback = restored.due().getFirst();
        assertThat(callback.duplicates()).isEqualTo(2);
        restored.acknowledged(callback, true);
        assertThat(ledger().due()).isEmpty();
        assertThatThrownBy(
                        () -> restored.configure(mapper.readTree("{\"duplicateCallbacks\":100}")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void productionRefusesTheSimulator() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ProviderFixtureLedger(mapper, env, directory.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "null",
                "[]",
                "{\"dropCallbacks\":1}",
                "{\"reverseCallbacks\":\"true\"}",
                "{\"unknown\":true}",
                "{\"delayMs\":-1}",
                "{\"delayMs\":30001}",
                "{\"delayMs\":\"slow\"}",
                "{\"responseDelayMs\":-1}",
                "{\"responseDelayMs\":5001}",
                "{\"responseDelayMs\":\"slow\"}",
                "{\"refundOutcome\":\"unknown\"}",
                "{\"duplicateCallbacks\":-1}",
                "{\"duplicateCallbacks\":4}",
                "{\"duplicateCallbacks\":1.5}"
            })
    void malformedControlsCannotExpandTheFixtureWorkload(String json) throws Exception {
        var ledger = ledger();
        assertThatThrownBy(() -> ledger.configure(mapper.readTree(json)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(ledger.responseDelay()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "amount",
                "currency",
                "payment_method",
                "metadata[payment_id]",
                "metadata[order_id]",
                "metadata[customer_id]",
                "key"
            })
    void malformedChargeCannotCreateAResource(String field) throws Exception {
        var ledger = ledger();
        var request = payment("pm_success");
        if (!field.equals("key")) request.put(field, "invalid");
        assertThatThrownBy(
                        () -> ledger.charge(request, field.equals("key") ? "bad key" : "valid-key"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(ledger.stats().get("resources")).isEqualTo(0);
    }

    @Test
    void processingAndFailureOutcomesSurviveReorderingAndCancellation() throws Exception {
        var ledger = ledger();
        ledger.configure(mapper.readTree("{\"delayMs\":0,\"reverseCallbacks\":true}"));
        String processing =
                ledger.charge(payment("pm_processing"), "processing").path("id").asText();
        var due = ledger.due();
        assertThat(due).hasSize(2);
        assertThat(due.getFirst().payload().path("type").asText())
                .isEqualTo("payment_intent.succeeded");
        due.forEach(event -> ledger.acknowledged(event, true));
        assertThat(ledger.get(processing).path("status").asText()).isEqualTo("succeeded");
        String failed = ledger.charge(payment("pm_fail_late"), "late-failure").path("id").asText();
        ledger.due().forEach(event -> ledger.acknowledged(event, true));
        assertThat(ledger.get(failed).path("status").asText()).isEqualTo("requires_payment_method");
        assertThat(ledger.cancel(failed).path("status").asText()).isEqualTo("canceled");
        assertThat(ledger.cancel(failed).path("status").asText()).isEqualTo("canceled");
        assertThatThrownBy(() -> ledger.cancel(processing))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> ledger.cancel("unknown"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> ledger.get("unknown"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(ledger.due(0)).isEmpty();
        assertThat(ledger.due(21)).isEmpty();
    }

    @Test
    void failedScaNeverCapturesAndRefundFailureReleasesTheReservedBalance() throws Exception {
        var ledger = ledger();
        ledger.configure(mapper.readTree("{\"delayMs\":0,\"refundOutcome\":\"failed\"}"));
        String action = ledger.charge(payment("pm_action"), "action-failed").path("id").asText();
        assertThat(
                        ledger.complete(action, false)
                                .path("last_payment_error")
                                .path("decline_code")
                                .asText())
                .isEqualTo("authentication_failed");
        assertThatThrownBy(() -> ledger.complete("unknown", true))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        String captured = ledger.charge(payment("pm_success"), "captured").path("id").asText();
        var request =
                new HashMap<>(
                        Map.of(
                                "payment_intent",
                                captured,
                                "amount",
                                "1000",
                                "metadata[payment_id]",
                                UUID.randomUUID().toString(),
                                "metadata[refund_id]",
                                UUID.randomUUID().toString()));
        String refund = ledger.refund(request, "first-refund").path("id").asText();
        var callbacks = ledger.due();
        callbacks.forEach(event -> ledger.acknowledged(event, true));
        assertThat(ledger.get(refund).path("status").asText()).isEqualTo("failed");
        assertThat(callbacks)
                .anyMatch(event -> event.payload().path("type").asText().equals("refund.failed"));
        ledger.configure(mapper.readTree("{\"refundOutcome\":\"succeeded\"}"));
        String replacement = ledger.refund(request, "replacement").path("id").asText();
        ledger.due().forEach(event -> ledger.acknowledged(event, true));
        assertThat(ledger.get(replacement).path("status").asText()).isEqualTo("succeeded");
        request.put("metadata[refund_id]", "invalid");
        request.put("amount", "1");
        request.put(
                "payment_intent",
                ledger.charge(payment("pm_success"), "other").path("id").asText());
        assertThatThrownBy(() -> ledger.refund(request, "invalid-metadata"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        request.put("payment_intent", action);
        assertThatThrownBy(() -> ledger.refund(request, "unpaid"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void failedDiskCommitDoesNotPublishAPhantomPayment() throws Exception {
        var ledger = ledger();
        var backup = directory.resolveSibling(directory.getFileName() + "-backup");
        java.nio.file.Files.move(directory, backup);
        java.nio.file.Files.writeString(directory, "not-a-directory");
        try {
            assertThatThrownBy(() -> ledger.charge(payment("pm_success"), "disk-failed"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Provider fixture persistence unavailable");
            assertThat(ledger.stats().get("resources")).isEqualTo(0);
        } finally {
            java.nio.file.Files.delete(directory);
            java.nio.file.Files.move(backup, directory);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullLedgerRejectsMoreWorkBeforeMutatingStorage() throws Exception {
        var ledger = ledger();
        var resources =
                (Map<String, com.fasterxml.jackson.databind.node.ObjectNode>)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                ledger, "resources");
        for (int i = 0; i < 5000; i++) resources.put("pi_capacity_" + i, mapper.createObjectNode());
        assertThatThrownBy(() -> ledger.charge(payment("pm_success"), "over-capacity"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(ledger.stats().get("resources")).isEqualTo(5000);
    }

    @Test
    void configuredReplyDelayDoesNotAllocateAnotherProviderResource() throws Exception {
        var ledger = ledger();
        ledger.configure(mapper.readTree("{\"responseDelayMs\":1,\"dropCallbacks\":true}"));
        var controller = new ProviderFixtureController(ledger);
        var request = payment("pm_timeout");
        String intent = controller.charge(request, "slow-reply").path("id").asText();
        assertThat(controller.payment(intent).path("status").asText()).isEqualTo("succeeded");
        var refund =
                controller.refund(
                        Map.of(
                                "payment_intent",
                                intent,
                                "amount",
                                "100",
                                "metadata[payment_id]",
                                UUID.randomUUID().toString(),
                                "metadata[refund_id]",
                                UUID.randomUUID().toString()),
                        "slow-refund");
        assertThat(controller.refund(refund.path("id").asText()).path("status").asText())
                .isEqualTo("pending");
        assertThat(controller.stats().get("payments")).isEqualTo(1L);
    }
}
