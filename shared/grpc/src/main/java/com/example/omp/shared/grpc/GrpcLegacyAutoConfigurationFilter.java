package com.example.omp.shared.grpc;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;

/** Replaces only the starter integrations that reference removed Boot/Security types. */
public final class GrpcLegacyAutoConfigurationFilter implements AutoConfigurationImportFilter {
    @Override
    public boolean[] match(String[] names, AutoConfigurationMetadata metadata) {
        boolean[] matches = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            matches[i] =
                    !"net.devh.boot.grpc.server.autoconfigure.GrpcServerSecurityAutoConfiguration"
                                    .equals(names[i])
                            && !"net.devh.boot.grpc.client.autoconfigure.GrpcClientHealthAutoConfiguration"
                                    .equals(names[i]);
        }
        return matches;
    }
}
