package com.example.omp.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Standalone JDK-only subscriber, run inside the packaged journey's Docker network. */
public final class ContainerSubscriber {
    private ContainerSubscriber() {}

    public static void main(String[] args) throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress(Integer.parseInt(args[0])), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(
                "/hook",
                exchange -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String signature =
                            Objects.toString(
                                    exchange.getRequestHeaders().getFirst("X-Signature-256"), "");
                    String timestamp =
                            Objects.toString(
                                    exchange.getRequestHeaders().getFirst("X-Timestamp"), "");
                    received.add(
                            Base64.getEncoder().encodeToString(body)
                                    + "\t"
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    signature.getBytes(StandardCharsets.UTF_8))
                                    + "\t"
                                    + timestamp);
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.createContext(
                "/requests",
                exchange -> {
                    byte[] body = String.join("\n", received).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.createContext(
                "/health",
                exchange -> {
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    server.stop(0);
                                    executor.shutdownNow();
                                }));
        server.start();
    }
}
