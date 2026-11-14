package com.example.omp.gateway.config;

import com.example.omp.gateway.graphql.error.ClientConflictException;
import com.example.omp.gateway.graphql.error.ClientNotFoundException;
import com.example.omp.gateway.graphql.error.ClientValidationException;
import com.example.omp.gateway.graphql.error.DownstreamServiceException;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Translates Feign HTTP errors into the gateway's typed exceptions.
 *
 * <p>Mapping:
 *
 * <ul>
 *   <li>403 -> GraphQL FORBIDDEN without forwarding the upstream body
 *   <li>404 â†’ {@link ClientNotFoundException} (â†’ GraphQL {@code NOT_FOUND})
 *   <li>409 â†’ {@link ClientConflictException} (â†’ GraphQL {@code CONFLICT})
 *   <li>400, 422 â†’ {@link ClientValidationException} (â†’ GraphQL {@code VALIDATION_FAILED})
 *   <li>5xx or other â†’ {@link DownstreamServiceException} (â†’ GraphQL {@code
 *       DOWNSTREAM_SERVICE_ERROR})
 * </ul>
 *
 * <p>The service name is extracted from the Feign method key, which has the form {@code
 * "CustomerClient#getCustomer(UUID)"}. We parse the class name and map it to the downstream service
 * name for logging.
 *
 * <p>This bean is picked up automatically by Spring Cloud OpenFeign's auto-configuration. No
 * explicit wiring is needed.
 */
@Configuration
public class FeignErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(FeignErrorDecoder.class);

    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> {
            int status = response.status();
            String body = readBody(response);
            String service = serviceFromMethodKey(methodKey);

            log.debug(
                    "Feign error: service={} status={} method={} body={}",
                    service,
                    status,
                    methodKey,
                    truncate(body, 500));

            String message = buildMessage(service, status, body);

            if (status == 403) {
                return new org.springframework.security.access.AccessDeniedException(
                        "Access denied");
            }
            if (status == 404) {
                return new ClientNotFoundException(message);
            }
            if (status == 409) {
                return new ClientConflictException(message);
            }
            if (status == 400 || status == 422) {
                return new ClientValidationException(message);
            }
            // 5xx, timeouts, unexpected 4xx â€” treat as downstream failure
            return new DownstreamServiceException(service, status, message);
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the response body as a UTF-8 string. Returns a placeholder if the body isn't readable
     * (unusual, but possible for streamed responses).
     */
    private static String readBody(Response response) {
        if (response.body() == null) {
            return "(no body)";
        }
        try (InputStream is = response.body().asInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(unable to read body: " + e.getMessage() + ")";
        }
    }

    /**
     * Extracts the downstream service name from the Feign method key.
     *
     * <p>The method key has the form {@code "ProductClient#getProduct(UUID)"} or {@code
     * "CustomerClient#listCustomers(String,int,int)"}. The part before {@code "#"} is the client
     * interface name.
     */
    private static String serviceFromMethodKey(String methodKey) {
        if (methodKey == null || methodKey.isBlank()) {
            return "unknown";
        }
        int hashIndex = methodKey.indexOf('#');
        String clientName = hashIndex > 0 ? methodKey.substring(0, hashIndex) : methodKey;

        return switch (clientName) {
            case "ProductClient" -> "product-service";
            case "CustomerClient" -> "customer-service";
            case "OrderClient" -> "order-service";
            default -> clientName;
        };
    }

    /**
     * Builds a human-readable message that includes the downstream service name and a truncated
     * error body. The body is often a Problem Details JSON object, which the client can display or
     * log.
     */
    private static String buildMessage(String service, int status, String body) {
        String cleanBody = body != null ? body.trim() : "";
        if (cleanBody.isEmpty()) {
            return "Downstream service '" + service + "' returned HTTP " + status;
        }
        return "Downstream service '"
                + service
                + "' returned HTTP "
                + status
                + ": "
                + truncate(cleanBody, 500);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
