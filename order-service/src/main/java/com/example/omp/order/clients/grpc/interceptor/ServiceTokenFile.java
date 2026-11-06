package com.example.omp.order.clients.grpc.interceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded token projection reader. The downstream issuer validation remains authoritative. */
public final class ServiceTokenFile {
    private ServiceTokenFile() {}

    public static String read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384) throw new IOException("Service token exceeds bound");
            String token = new String(bytes, StandardCharsets.UTF_8).strip();
            if (!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw new IOException("Invalid service token format");
            return token;
        }
    }
}
