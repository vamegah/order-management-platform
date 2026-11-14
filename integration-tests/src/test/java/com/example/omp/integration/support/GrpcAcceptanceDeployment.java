package com.example.omp.integration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Process controls are restricted to one explicitly opted-in disposable acceptance deployment. */
public final class GrpcAcceptanceDeployment {
    private static final Set<String> SERVICES =
            Set.of("inventory-service", "payment-service", "order-service");
    private final String project;
    private final String docker = System.getenv().getOrDefault("E2E_DOCKER", "docker");
    private final Path issuer;

    public GrpcAcceptanceDeployment() throws Exception {
        project = System.getenv().getOrDefault("COMPOSE_PROJECT_NAME", "");
        if (!"true".equals(System.getenv("E2E_GRPC_CONTROLS"))
                || !project.matches("omp-grpc-acceptance-[a-f0-9]{12}"))
            throw new IllegalStateException(
                    "gRPC acceptance requires an opted-in isolated deployment");
        issuer =
                Path.of(Objects.requireNonNull(System.getenv("E2E_GRPC_ISSUER_DIRECTORY")))
                        .toRealPath();
        if (issuer.getParent() == null || !Files.isRegularFile(issuer.resolve("ca/ca.key")))
            throw new IllegalStateException("An isolated issuer directory is required");
        for (String service : SERVICES) container(service);
    }

    public Path issuer() {
        return issuer;
    }

    public String container(String service) throws Exception {
        if (!SERVICES.contains(service))
            throw new IllegalArgumentException("Unknown gRPC workload");
        String id =
                execute(
                                List.of(
                                        docker,
                                        "ps",
                                        "-aq",
                                        "--filter",
                                        "label=com.docker.compose.project=" + project,
                                        "--filter",
                                        "label=com.docker.compose.service=" + service,
                                        "--filter",
                                        "label=com.docker.compose.oneoff=False"),
                                30)
                        .trim();
        if (!id.matches("[a-f0-9]{12,64}"))
            throw new IllegalStateException("Require exactly one isolated workload");
        var labels =
                new ObjectMapper()
                        .readTree(
                                execute(
                                        List.of(
                                                docker,
                                                "inspect",
                                                "--format",
                                                "{{json .Config.Labels}}",
                                                id),
                                        30));
        if (!project.equals(labels.path("com.docker.compose.project").asText())
                || !service.equals(labels.path("com.docker.compose.service").asText()))
            throw new IllegalStateException("Workload ownership changed");
        return id;
    }

    public String startedAt(String service) throws Exception {
        return execute(
                        List.of(
                                docker,
                                "inspect",
                                "--format",
                                "{{.State.StartedAt}}",
                                container(service)),
                        30)
                .trim();
    }

    public void pause(String service) throws Exception {
        execute(List.of(docker, "pause", container(service)), 30);
    }

    public void unpause(String service) throws Exception {
        execute(List.of(docker, "unpause", container(service)), 30);
    }

    private List<String> issuerCommand(String script, String... arguments) {
        var command = new ArrayList<String>();
        if ("true".equals(System.getenv("E2E_GRPC_ISSUER_SUDO"))) {
            command.addAll(
                    List.of(
                            "sudo",
                            "--preserve-env=E2E_GRPC_CONTROLS,E2E_GRPC_ISSUER_DIRECTORY,COMPOSE_PROJECT_NAME"));
        }
        command.add(System.getenv().getOrDefault("E2E_PYTHON", "python3"));
        command.add(script);
        command.addAll(List.of(arguments));
        command.addAll(List.of("--tls-dir", issuer.toString()));
        return command;
    }

    public void renew() throws Exception {
        execute(issuerCommand("scripts/rotate_grpc_certificates.py", "--force"), 120);
    }

    public void rotateCa(String phase) throws Exception {
        if (!Set.of("prepare", "activate", "retire").contains(phase))
            throw new IllegalArgumentException("Unknown CA phase");
        execute(issuerCommand("scripts/grpc_acceptance_ca_rotation.py", phase), 120);
    }

    public boolean reloadedAfter(String service, java.time.Instant marker) throws Exception {
        String logs =
                execute(
                        List.of(
                                docker,
                                "logs",
                                "--since",
                                marker.toString(),
                                "--tail",
                                "1000",
                                container(service)),
                        30);
        return logs.contains("Loaded validated gRPC TLS identity and trust projection");
    }

    public void replace(String service) throws Exception {
        String previous = container(service);
        var command =
                new ArrayList<>(
                        List.of(
                                docker,
                                "compose",
                                "--project-name",
                                project,
                                "--env-file",
                                ".env.example",
                                "-f",
                                "docker-compose.yml",
                                "-f",
                                "docker-compose.e2e.yml",
                                "-f",
                                "docker-compose.grpc-e2e.yml"));
        String override = System.getenv("E2E_GRPC_COMPOSE_OVERRIDE");
        if (override != null) {
            Path path = Path.of(override).toAbsolutePath().normalize();
            if (!path.startsWith(Path.of(".scratch").toAbsolutePath().normalize())
                    || !Files.isRegularFile(path))
                throw new IllegalArgumentException(
                        "Acceptance override must exist inside .scratch");
            command.addAll(List.of("-f", path.toString()));
        }
        command.addAll(
                List.of(
                        "up",
                        "-d",
                        "--no-deps",
                        "--force-recreate",
                        "--no-build",
                        "--wait",
                        "--wait-timeout",
                        "240",
                        service));
        execute(command, 270);
        if (previous.equals(container(service)))
            throw new IllegalStateException("Container was not replaced");
    }

    public String recoveryToken() throws Exception {
        var realm =
                new ObjectMapper()
                        .readTree(
                                Files.readString(
                                        Path.of("infrastructure/keycloak/import/omp-realm.json")));
        String secret = null;
        for (var client : realm.path("clients"))
            if ("order-recovery".equals(client.path("clientId").asText()))
                secret = client.path("secret").asText();
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("Missing isolated development recovery identity");
        var response =
                io.restassured.RestAssured.given()
                        .contentType("application/x-www-form-urlencoded")
                        .formParams(
                                Map.of(
                                        "grant_type",
                                        "client_credentials",
                                        "client_id",
                                        "order-recovery",
                                        "client_secret",
                                        secret))
                        .post(
                                System.getenv()
                                                .getOrDefault(
                                                        "KEYCLOAK_URL", "http://keycloak.localhost")
                                        + "/realms/omp/protocol/openid-connect/token");
        if (response.statusCode() != 200)
            throw new IllegalStateException("Recovery issuer rejected the development identity");
        String token = response.path("access_token");
        if (token == null || token.isBlank())
            throw new IllegalStateException("Recovery identity missing");
        return token;
    }

    private static String execute(List<String> command, int seconds) throws Exception {
        Path result = Files.createTempFile("omp-grpc-control-", ".log");
        try {
            var process =
                    new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .redirectOutput(result.toFile())
                            .start();
            try {
                if (!process.waitFor(seconds, TimeUnit.SECONDS))
                    throw new IllegalStateException("Acceptance operation timed out");
                if (process.exitValue() != 0)
                    throw new IllegalStateException("Acceptance operation failed");
                if (Files.size(result) > 1048576)
                    throw new IllegalStateException("Unbounded acceptance command output");
                return Files.readString(result, StandardCharsets.UTF_8);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.deleteIfExists(result);
        }
    }
}
