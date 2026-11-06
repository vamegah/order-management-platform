package com.example.omp.notification.clients;

import com.example.omp.notification.clients.dto.CustomerDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * REST client for customer-service.
 *
 * <p>The {@code /me} endpoint is used to resolve the JWT's user to a customer ID (see {@code
 * OwnershipVerifier}).
 *
 * <p>Interceptors: the JWT from the current STOMP session is forwarded via {@link
 * NotificationFeignConfig}.
 */
@FeignClient(
        name = "customer-service",
        url = "${omp.clients.customer-service.url}",
        configuration = NotificationFeignConfig.class)
public interface CustomerClient {

    /**
     * Resolves the current JWT's user to a customer. Requires the JWT to be forwarded via the Feign
     * interceptor.
     */
    @GetMapping("/api/v1/customers/me")
    CustomerDto me();
}
