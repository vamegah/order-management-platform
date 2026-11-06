package com.example.omp.order.config;

import com.example.omp.order.error.DownstreamServiceException;
import com.example.omp.order.error.InvalidOrderDataException;
import com.example.omp.order.error.ProductUnavailableException;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Translates Feign HTTP errors into domain exceptions.
 *
 * <p>Mapping:
 *
 * <ul>
 *   <li>404 from product-client → {@link ProductUnavailableException} (translated to 400 by the
 *       advice — the requested product doesn't exist)
 *   <li>4xx (other) → {@link InvalidOrderDataException} (400)
 *   <li>5xx → {@link DownstreamServiceException} (502)
 * </ul>
 */
@Configuration
public class FeignErrorDecoder {

    @Bean
    public ErrorDecoder decoder() {
        return (methodKey, response) -> {
            String body = readBody(response);
            int status = response.status();

            // Extract the target service from the Feign method key.
            // Format: "ProductClient#getProduct(UUID)"
            String service = extractService(methodKey);

            if (status == 404 && "product-service".equals(service)) {
                return new ProductUnavailableException("Product not found: " + body);
            }
            if (status >= 400 && status < 500) {
                return new InvalidOrderDataException("Invalid data for " + service + ": " + body);
            }
            return new DownstreamServiceException(service, status, body);
        };
    }

    private String extractService(String methodKey) {
        // "ProductClient#getProduct(UUID)" -> "product-service"
        if (methodKey.startsWith("ProductClient")) return "product-service";
        if (methodKey.startsWith("CustomerClient")) return "customer-service";
        return "unknown";
    }

    private String readBody(Response response) {
        try (var body = response.body().asInputStream()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unable to read body)";
        }
    }
}
