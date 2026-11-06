package com.example.omp.shared.grpc;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/** The existing metrics integrations need Boot 4's registry definitions before their conditions. */
@AutoConfiguration(
        afterName =
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        beforeName = {
            "net.devh.boot.grpc.server.autoconfigure.GrpcServerMetricAutoConfiguration",
            "net.devh.boot.grpc.client.autoconfigure.GrpcClientMetricAutoConfiguration"
        })
public class GrpcMetricsOrderingAutoConfiguration {}
