package com.example.omp.notification.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSubscriptionRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank
                @Pattern(
                        regexp = "^https?://.+",
                        message = "targetUrl must start with http:// or https://")
                String targetUrl,
        @NotEmpty List<String> eventTypes) {}
