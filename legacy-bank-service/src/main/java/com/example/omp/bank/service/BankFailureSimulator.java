package com.example.omp.bank.service;

import org.springframework.stereotype.Component;

/** Fault controls are database-only, explicitly disabled outside disposable acceptance. */
@Component
public class BankFailureSimulator {
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final boolean enabled;

    public BankFailureSimulator(
            org.springframework.jdbc.core.JdbcTemplate db,
            @org.springframework.beans.factory.annotation.Value(
                            "${bank.simulator.controls-enabled:false}")
                    boolean enabled) {
        this.db = db;
        this.enabled = enabled;
    }

    public Failure next() {
        if (!enabled) return new Failure("normal", 0);
        var rows =
                db.queryForList(
                        "UPDATE bank_simulator_control SET remaining=remaining-1 WHERE id=1 AND remaining>0 RETURNING mode,delay_ms");
        return rows.isEmpty()
                ? new Failure("normal", 0)
                : new Failure(
                        (String) rows.getFirst().get("mode"),
                        ((Number) rows.getFirst().get("delay_ms")).intValue());
    }

    public record Failure(String mode, int delay) {
        public void before() {
            if (mode.equals("backend"))
                throw new BankingFaultException("UPSTREAM_ERROR", "Private simulator failure");
            if (mode.equals("slow")) pause();
        }

        public void after() {
            if (mode.equals("lost-reply")) pause();
        }

        private void pause() {
            try {
                Thread.sleep(Math.min(10000, Math.max(0, delay)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BankingFaultException("UPSTREAM_ERROR", "Interrupted");
            }
        }
    }
}
