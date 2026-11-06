package com.example.omp.notification.webhook.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateSubscriptionRequest(
        @Size(max = 200) String name,
        @Pattern(regexp = "^https?://.+", message = "targetUrl must start with http:// or https://")
                String targetUrl,
        List<String> eventTypes) {}
