package com.example.omp.shared.grpc;

import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/**
 * The legacy observation interceptors eagerly create the registry inside the client postprocessor,
 * before Boot can attach its HTTP/Feign tracing handlers. OMP provides callback-scoped tracing; the
 * independent legacy metric collectors and all authentication/transport configuration remain.
 */
public final class GrpcLegacyObservationFilter implements AutoConfigurationImportFilter {
    private static final Set<String> REPLACED =
            Set.of(
                    "net.devh.boot.grpc.client.autoconfigure.GrpcClientMicrometerTraceAutoConfiguration");

    @Override
    public boolean[] match(String[] candidates, AutoConfigurationMetadata metadata) {
        boolean[] result = new boolean[candidates.length];
        for (int i = 0; i < candidates.length; i++) {
            result[i] = candidates[i] == null || !REPLACED.contains(candidates[i]);
        }
        return result;
    }
}
