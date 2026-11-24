package com.example.omp.payment.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(20)
public class ProviderWebhookNetworkFilter extends OncePerRequestFilter {
    private final List<org.springframework.security.web.util.matcher.IpAddressMatcher> allowed;
    private final MeterRegistry metrics;

    public ProviderWebhookNetworkFilter(
            @Value("${payment.webhook.allowed-networks:}") String networks, MeterRegistry metrics) {
        this.metrics = metrics;
        allowed =
                Arrays.stream(networks.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(org.springframework.security.web.util.matcher.IpAddressMatcher::new)
                        .toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/v1/payments/webhooks/")
                || allowed.isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // Only the socket peer is trusted. X-Forwarded-For never grants access.
        if (allowed.stream().noneMatch(matcher -> matcher.matches(request.getRemoteAddr()))) {
            metrics.counter("payment.webhook.rejections", "reason", "network").increment();
            response.sendError(403);
            return;
        }
        chain.doFilter(request, response);
    }
}
