package com.example.omp.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProviderFixtureController {
    private final ProviderFixtureLedger ledger;

    public ProviderFixtureController(ProviderFixtureLedger ledger) {
        this.ledger = ledger;
    }

    @PostMapping(
            value = "/v1/payment_intents",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public JsonNode charge(
            @RequestParam Map<String, String> request, @RequestHeader("Idempotency-Key") String key)
            throws InterruptedException {
        JsonNode result = ledger.charge(request, key);
        long delay = ledger.responseDelay();
        if ("pm_timeout".equals(request.get("payment_method"))) delay = Math.max(2000, delay);
        if (delay > 0) Thread.sleep(delay);
        return result;
    }

    @PostMapping("/v1/payment_intents/{id}/cancel")
    public JsonNode cancel(@PathVariable String id) {
        return ledger.cancel(id);
    }

    @GetMapping("/v1/payment_intents/{id}")
    public JsonNode payment(@PathVariable String id) {
        return ledger.get(id);
    }

    @PostMapping(value = "/v1/refunds", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public JsonNode refund(
            @RequestParam Map<String, String> request, @RequestHeader("Idempotency-Key") String key)
            throws InterruptedException {
        JsonNode result = ledger.refund(request, key);
        long delay = ledger.responseDelay();
        if (delay > 0) Thread.sleep(delay);
        return result;
    }

    @GetMapping("/v1/refunds/{id}")
    public JsonNode refund(@PathVariable String id) {
        return ledger.get(id);
    }

    @PostMapping("/__test/payment_intents/{id}/complete")
    public JsonNode complete(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        return ledger.complete(id, Boolean.TRUE.equals(body.get("success")));
    }

    @PutMapping("/__test/controls")
    public JsonNode configure(@RequestBody JsonNode controls) {
        return ledger.configure(controls);
    }

    @GetMapping("/__test/stats")
    public Map<String, Object> stats() {
        return ledger.stats();
    }
}
