package com.example.omp.integration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Container controls restricted to the explicitly opted-in, isolated acceptance project. */
public final class RecoveryDeployment {
    private static final Set<String> SERVICES =
            Set.of("order-service", "inventory-service", "payment-service", "order-recovery-token");
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
