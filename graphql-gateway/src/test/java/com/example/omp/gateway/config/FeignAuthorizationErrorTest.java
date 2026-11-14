package com.example.omp.gateway.config;

import static org.assertj.core.api.Assertions.*;

import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class FeignAuthorizationErrorTest {
    @Test
    void upstreamOwnershipDenialRetainsItsMeaningWithoutPrivateBodyDetails() {
        var request =
                Request.create(
                        Request.HttpMethod.GET,
                        "http://customer.invalid/api/v1/customers/id",
                        Map.of(),
                        null,
                        StandardCharsets.UTF_8,
                        null);
        var response =
                Response.builder()
                        .request(request)
                        .status(403)
                        .reason("Forbidden")
                        .body(
                                "{\"detail\":\"private upstream authorization context\"}",
                                StandardCharsets.UTF_8)
                        .build();
        var error =
                new FeignErrorDecoder()
                        .errorDecoder()
                        .decode("CustomerClient#getCustomer(UUID)", response);
        assertThat(error).isInstanceOf(AccessDeniedException.class).hasMessage("Access denied");
    }
}
