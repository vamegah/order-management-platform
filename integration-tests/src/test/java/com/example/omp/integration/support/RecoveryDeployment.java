package com.example.omp.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Container controls restricted to the explicitly opted-in, isolated acceptance project. */
public final class RecoveryDeployment {
    private static final Set<String> SERVICES =
            Set.of(
                    "order-service",
                    "inventory-service",
                    "payment-service",
                    "order-recovery-token",
                    "postgres");
    private final String project;
    private final String docker;

    public RecoveryDeployment() throws Exception {
        project = System.getenv().getOrDefault("COMPOSE_PROJECT_NAME", "");
        docker = System.getenv().getOrDefault("E2E_DOCKER", "docker");
        if (!"true".equals(System.getenv("E2E_RECOVERY_RESTARTS"))
                || !project.matches("omp-(e2e-[0-9]+-[0-9]+|recovery-acceptance-[a-f0-9]{12})")) {
            throw new IllegalStateException(
                    "Scheduled recovery requires an isolated opted-in project");
        }
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("GRPC_TLS_ENABLED", "true"))) {
            throw new IllegalStateException("Scheduled recovery acceptance requires mTLS");
        }
        compose(); // Validate any local override before allowing a container operation.
        for (String service : SERVICES) container(service);
    }

    public void stop(String service) throws Exception {
        execute(List.of("stop", "--time", "10", container(service)), 45);
    }

    public void start(String service) throws Exception {
        String id = container(service);
        execute(List.of("start", id), 30);
        awaitHealthy(id);
    }

    public String startedAt(String service) throws Exception {
        return execute(
                        List.of("inspect", "--format", "{{.State.StartedAt}}", container(service)),
                        15)
                .trim();
    }

    /** Emulate lost metadata only on an independent, unchanged two-line acceptance checkout. */
    public void clearTestOwnedReferences(
            UUID order,
            String marker,
            UUID firstLine,
            UUID firstHold,
            UUID secondLine,
            UUID secondHold)
            throws Exception {
        if (marker == null
                || !marker.matches("e2e-reference-repair-[a-f0-9-]{36}")
                || firstLine.equals(secondLine)
                || firstHold.equals(secondHold)) {
            throw new IllegalArgumentException("Independent reference-repair fixture required");
        }
        // UUID values and the restricted marker cannot contain SQL syntax. The fixed statement
        // also checks the exact fresh order, complete line set and original references atomically.
        sql(
                """
                BEGIN;
                SET LOCAL statement_timeout = '5s';
                DO $fixture$
                DECLARE touched integer; fixture_order uuid;
                BEGIN
                  SELECT id INTO fixture_order FROM orders.purchase_order
                  WHERE id = '%s' AND shipping_line1 = '%s' AND status = 'DRAFT' AND version = 0
                    AND EXISTS (SELECT 1 FROM orders.checkout_attempt
                                WHERE order_id = '%s' AND status = 'ACCEPTED')
                  FOR UPDATE;
                  IF NOT FOUND OR (SELECT count(*) FROM orders.order_line WHERE order_id = '%s') <> 2 THEN
                    RAISE EXCEPTION 'Fixture is not an independent fresh two-line checkout';
                  END IF;
                  -- Explicitly model captured pre-upgrade metadata in this privileged fixture.
                  -- Future checkouts are separately required to reject missing/unverified pointers.
                  INSERT INTO orders.legacy_lifecycle_resolution(order_id, original_status, original_version)
                    SELECT id, status, version FROM orders.purchase_order WHERE id = fixture_order;
                  INSERT INTO orders.legacy_lifecycle_line
                    (line_id, order_id, product_id, sku, quantity, original_reservation_id, original_warehouse)
                    SELECT id, order_id, product_id, product_sku, quantity, reservation_id, reservation_warehouse_id
                    FROM orders.order_line WHERE order_id = fixture_order;
                  UPDATE orders.lifecycle_watch SET next_scan_at = clock_timestamp() + interval '1 day'
                    WHERE order_id = fixture_order;
                  UPDATE orders.order_line SET reservation_verified = false WHERE order_id = fixture_order;
                  UPDATE orders.order_line SET reservation_id = NULL
                  WHERE order_id = '%s' AND
                    ((id = '%s' AND reservation_id = '%s') OR (id = '%s' AND reservation_id = '%s'));
                  GET DIAGNOSTICS touched = ROW_COUNT;
                  IF touched <> 2 THEN RAISE EXCEPTION 'Fixture references changed'; END IF;
                END $fixture$;
                COMMIT;
                """
                        .formatted(
                                order,
                                marker,
                                order,
                                order,
                                order,
                                firstLine,
                                firstHold,
                                secondLine,
                                secondHold));
    }

    /** Bounded rows for this order; outbox publication bookkeeping is deliberately excluded. */
    public JsonNode referenceSnapshot(UUID order) throws Exception {
        String id = order.toString();
        var values = new ArrayList<String>();
        values.add(
                "'order', (SELECT to_jsonb(o) FROM orders.purchase_order o WHERE id = '"
                        + id
                        + "')");
        for (String table :
                List.of(
                        "order_line",
                        "order_status_history",
                        "checkout_attempt",
                        "cleanup_task",
                        "fulfillment_request",
                        "fulfillment_task",
                        "reservation_reference_repair",
                        "terminal_reservation_reference_disposition")) {
            String key =
                    table.equals("checkout_attempt") || table.equals("fulfillment_request")
                            ? "order_id"
                            : "id";
            values.add(
                    "'"
                            + table
                            + "', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY "
                            + key
                            + "), '[]'::jsonb) FROM (SELECT * FROM orders."
                            + table
                            + " WHERE order_id = '"
                            + id
                            + "' LIMIT 101) t)");
        }
        values.add(
                "'outbox', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id), '[]'::jsonb) "
                        + "FROM (SELECT id, aggregate_id, aggregate_type, event_type, topic, payload, created_at FROM orders.outbox_event "
                        + "WHERE aggregate_id = '"
                        + id
                        + "' LIMIT 101) t)");
        values.add(
                "'stock', (SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY sku, warehouse_id), '[]'::jsonb) "
                        + "FROM (SELECT * FROM inventory.stock_level WHERE sku IN "
                        + "(SELECT product_sku FROM orders.order_line WHERE order_id = '"
                        + id
                        + "') LIMIT 101) t)");
        return new ObjectMapper()
                .readTree(sql("SELECT jsonb_build_object(" + String.join(",", values) + ");"));
    }

    public JsonNode lifecycleBacklog() throws Exception {
        return new ObjectMapper()
                .readTree(
                        sql(
                                """
            SELECT jsonb_build_object(
                'active_orders', (SELECT count(*) FROM orders.purchase_order WHERE status IN ('DRAFT','PLACED','CONFIRMED','FULFILLING','CANCELLING')),
                'pending_cleanup', (SELECT count(*) FROM orders.cleanup_task WHERE NOT completed),
                'uncompensated_cancellations', (SELECT count(*) FROM orders.lifecycle_watch w
                    JOIN orders.purchase_order o ON o.id = w.order_id WHERE o.status = 'CANCELLED' AND NOT w.compensation_ready));
            """));
    }

    private String sql(String statement) throws Exception {
        var process =
                new ProcessBuilder(
                                docker,
                                "exec",
                                "-i",
                                container("postgres"),
                                "sh",
                                "-c",
                                "exec psql -X -w -qAt -v ON_ERROR_STOP=1 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(statement.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Isolated reference fixture command failed");
            byte[] result = process.getInputStream().readNBytes(16385);
            if (result.length > 16384) throw new IOException("Unexpected reference fixture output");
            return new String(result, StandardCharsets.UTF_8).trim();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Read-only census of immutable upgrade eligibility and final reference integrity. */
    public JsonNode legacyLifecycleIntegrity() throws Exception {
        return new ObjectMapper()
                .readTree(
                        sql(
                                """
            SELECT jsonb_build_object(
                'unresolved', (SELECT count(*) FROM orders.legacy_lifecycle_resolution WHERE resolved_at IS NULL),
                'uncompensated_cancellations', (SELECT count(*) FROM orders.legacy_lifecycle_resolution legacy
                    JOIN orders.purchase_order o ON o.id = legacy.order_id JOIN orders.lifecycle_watch w ON w.order_id = o.id
                    WHERE o.status = 'CANCELLED' AND NOT w.compensation_ready),
                'pending_cleanup', (SELECT count(*) FROM orders.cleanup_task t
                    JOIN orders.legacy_lifecycle_resolution legacy ON legacy.order_id = t.order_id WHERE NOT t.completed),
                'active_unverified', (SELECT count(*) FROM orders.order_line l JOIN orders.purchase_order o ON o.id = l.order_id
                    WHERE o.status NOT IN ('DELIVERED','CANCELLED') AND (l.reservation_id IS NULL OR NOT l.reservation_verified)),
                'invented_warehouses', (SELECT count(*) FROM orders.legacy_lifecycle_line original JOIN orders.order_line l ON l.id = original.line_id
                    WHERE original.original_warehouse IS NULL AND l.reservation_warehouse_id IS NOT NULL),
                'audited_exceptions', (SELECT count(*) FROM orders.terminal_reservation_reference_disposition));
            """));
    }

    /** Emits a digest only; credentials and JWTs never leave their private container mounts. */
    public String projectionFingerprint() throws Exception {
        return execute(
                        List.of(
                                "exec",
                                container("order-recovery-token"),
                                "python",
                                "-B",
                                "-c",
                                "import hashlib,os,pathlib,stat; "
                                        + "p=pathlib.Path('/run/order-recovery/access.jwt'); "
                                        + "assert os.getuid()==1000; "
                                        + "assert stat.S_IMODE(p.stat().st_mode)==0o600; "
                                        + "assert stat.S_IMODE(p.parent.stat().st_mode)==0o700; "
                                        + "s=pathlib.Path(os.environ['SERVICE_TOKEN_CLIENT_SECRET_FILE']); "
                                        + "assert stat.S_IMODE(s.stat().st_mode)==0o400; "
                                        + "assert p.stat().st_uid==1000 and s.stat().st_uid==1000; "
                                        + "print(hashlib.sha256(p.read_bytes()).hexdigest())"),
                        15)
                .trim();
    }

    /** Stop the refresh loop before replacing its test-owned projection with invalid input. */
    public void suspendProjection() throws Exception {
        stop("order-recovery-token");
        var command = compose();
        command.addAll(
                List.of(
                        "run",
                        "--rm",
                        "--no-deps",
                        "order-recovery-token",
                        "python",
                        "-B",
                        "-c",
                        "import sys,pathlib; sys.path.insert(0,'/opt'); "
                                + "from project_service_token import replace_projection; "
                                + "replace_projection(pathlib.Path('/run/order-recovery/access.jwt'),'invalid-projection')"));
        execute(command, 60);
    }

    private List<String> compose() {
        var arguments = new ArrayList<String>();
        arguments.addAll(
                List.of("compose", "--project-name", project, "--env-file", ".env.example"));
        for (String file :
                List.of(
                        "docker-compose.yml",
                        "docker-compose.e2e.yml",
                        "docker-compose.recovery.yml",
                        "docker-compose.recovery-projector.yml",
                        "docker-compose.recovery-acceptance.yml")) {
            arguments.addAll(List.of("-f", file));
        }
        String override = System.getenv("E2E_RECOVERY_COMPOSE_OVERRIDE");
        if (override != null) {
            Path path = Path.of(override).toAbsolutePath().normalize();
            Path scratch = Path.of(".scratch").toAbsolutePath().normalize();
            if (!path.startsWith(scratch) || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException(
                        "Local recovery override must exist under .scratch");
            }
            arguments.addAll(List.of("-f", path.toString()));
        }
        return arguments;
    }

    private String container(String service) throws Exception {
        if (!SERVICES.contains(service))
            throw new IllegalArgumentException("Unknown recovery service");
        String id =
                execute(
                                List.of(
                                        "ps",
                                        "-aq",
                                        "--filter",
                                        "label=com.docker.compose.project=" + project,
                                        "--filter",
                                        "label=com.docker.compose.service=" + service,
                                        "--filter",
                                        "label=com.docker.compose.oneoff=False"),
                                15)
                        .trim();
        if (!id.matches("[a-f0-9]{12,64}")) {
            throw new IllegalStateException(
                    "Expected exactly one isolated container for " + service);
        }
        var labels =
                new ObjectMapper()
                        .readTree(
                                execute(
                                        List.of(
                                                "inspect",
                                                "--format",
                                                "{{json .Config.Labels}}",
                                                id),
                                        15));
        if (!project.equals(labels.path("com.docker.compose.project").asText())
                || !service.equals(labels.path("com.docker.compose.service").asText())) {
            throw new IllegalStateException("Container ownership changed");
        }
        return id;
    }

    private void awaitHealthy(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        while (System.nanoTime() < until) {
            String state =
                    execute(List.of("inspect", "--format", "{{.State.Health.Status}}", id), 15)
                            .trim();
            if ("healthy".equals(state)) return;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Recovery container did not become healthy");
    }

    private String execute(List<String> arguments, int timeoutSeconds) throws Exception {
        var command = new ArrayList<String>();
        command.add(docker);
        command.addAll(arguments);
        var process =
                new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // All supported commands produce bounded IDs/metadata; never inspect environments or logs.
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("Recovery container command timed out");
            }
            if (process.exitValue() != 0)
                throw new IOException("Recovery container command failed");
            byte[] result = process.getInputStream().readNBytes(16385);
            if (result.length > 16384) throw new IOException("Unexpected container command output");
            return new String(result, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
