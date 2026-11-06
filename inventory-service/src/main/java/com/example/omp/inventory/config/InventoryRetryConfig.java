package com.example.omp.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/** Retry advice wraps transaction advice so optimistic commit failures start a fresh attempt. */
@Configuration
@EnableRetry
public class InventoryRetryConfig {}
