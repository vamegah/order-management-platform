package com.example.omp.shared.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import feign.Headers;
import feign.RequestLine;
import feign.codec.Decoder;
import feign.codec.Encoder;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeignJsonAutoConfigurationTest {
    record Item(UUID id, Instant at, BigDecimal amount) {}

    interface Api {
        @RequestLine("POST /echo")
        @Headers("Content-Type: application/json")
        Item echo(Item body);

        @RequestLine("GET /items")
        List<Item> items();
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    Jackson2AutoConfiguration.class,
                                    FeignJsonAutoConfiguration.class))
                    .withPropertyValues(
                            "spring.jackson2.serialization.write-dates-as-timestamps=false");

    @Test
    void roundTripsJsonAndGenericResponsesOverRealHttpWithTheApplicationMapper() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        try {
            runner.run(
                    context -> {
                        assertThat(context).hasNotFailed();
                        var mapper = context.getBean(ObjectMapper.class);
                        var item =
                                new Item(
                                        UUID.randomUUID(),
                                        Instant.parse("2026-11-06T12:34:56Z"),
                                        new BigDecimal("12.34"));
                        var list = mapper.writeValueAsBytes(List.of(item));
                        server.createContext(
                                "/",
                                exchange -> {
                                    byte[] body;
                                    if (exchange.getRequestURI().getPath().equals("/echo")) {
                                        body = exchange.getRequestBody().readAllBytes();
                                        received.set(new String(body, StandardCharsets.UTF_8));
                                    } else {
                                        body = list;
                                    }
                                    exchange.getResponseHeaders()
                                            .set("Content-Type", "application/json");
                                    exchange.sendResponseHeaders(200, body.length);
                                    try (var output = exchange.getResponseBody()) {
                                        output.write(body);
                                    }
                                });
                        server.start();
                        var api =
                                Feign.builder()
                                        .encoder(context.getBean(Encoder.class))
                                        .decoder(context.getBean(Decoder.class))
                                        .target(
                                                Api.class,
                                                "http://127.0.0.1:"
                                                        + server.getAddress().getPort());
                        assertThat(api.echo(item)).isEqualTo(item);
                        assertThat(api.items()).containsExactly(item);
                        assertThat(received.get()).isEqualTo(mapper.writeValueAsString(item));
                        assertThat(received.get()).contains("2026-11-06T12:34:56Z", "12.34");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesExplicitClientCodecs() {
        var encoder = new Encoder.Default();
        var decoder = new Decoder.Default();
        runner.withBean(Encoder.class, () -> encoder)
                .withBean(Decoder.class, () -> decoder)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(Encoder.class)).isSameAs(encoder);
                            assertThat(context.getBean(Decoder.class)).isSameAs(decoder);
                        });
    }

    @Test
    void doesNotRequireFeignInServicesThatDoNotUseIt() {
        runner.withClassLoader(new FilteredClassLoader("feign"))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(FeignJsonAutoConfiguration.class);
                        });
    }
}
