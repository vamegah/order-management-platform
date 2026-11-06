package com.example.omp.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Programmable HTTP subscriber used as the target of outbound webhook deliveries in tests.
 *
 * <p>Response queue: enqueueResponse(200); enqueueResponse(503); enqueueResponse(200); ... each
 * incoming request pops the next entry; the queue is thread-safe.
 *
 * <p>If the queue is empty, the default response is 200 with an empty body.
 *
 * <p>Recorded requests: - bodies: every request body, in order - headers: every request's headers
 * (case-insensitive lookup) - times: every request's arrival timestamp (epoch millis)
 */
public final class MockSubscriber implements AutoCloseable {

    private final HttpServer server;
    private final String path;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private final ConcurrentLinkedQueue<Response> responseQueue = new ConcurrentLinkedQueue<>();
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
    public final List<Long> times = new CopyOnWriteArrayList<>();
    public final AtomicInteger requestCount = new AtomicInteger();

    public MockSubscriber() throws IOException {
        this("/hook");
    }

    public MockSubscriber(String path) throws IOException {
        this.path = path;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public void enqueueResponse(int status) {
        enqueueResponse(status, "");
    }

    public void enqueueResponse(int status, String body) {
        enqueueResponse(status, body, Duration.ZERO);
    }

    public void enqueueResponse(int status, String body, Duration delay) {
        responseQueue.add(new Response(status, body, delay));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void reset() {
        responseQueue.clear();
        bodies.clear();
        headers.clear();
        times.clear();
        requestCount.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // --- internals ---

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        headers.add(exchange.getRequestHeaders());
        times.add(System.currentTimeMillis());
        requestCount.incrementAndGet();

        Response response = responseQueue.poll();
        if (response == null) {
            response = new Response(200, "", Duration.ZERO);
        }

        try {
            Thread.sleep(response.delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }
        byte[] out = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private record Response(int status, String body, Duration delay) {}

    // --- helpers for assertions ---

    public JsonNode bodyAsJson(int index) throws IOException {
        return objectMapper.readTree(bodies.get(index));
    }

    public String header(int requestIndex, String name) {
        Map<String, List<String>> h = headers.get(requestIndex);
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().isEmpty() ? null : e.getValue().get(0);
            }
        }
        return null;
    }
}
