package com.example.omp.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local issuer: production JWT decoders fetch its public JWKS and verify real RSA signatures. */
public final class TestJwtIssuer implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;

    public TestJwtIssuer() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/jwks",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(jwks);
                    }
                });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String issuer() {
        return "http://localhost:" + port();
    }

    public String jwksUri() {
        return issuer() + "/jwks";
    }

    public String token(UUID customerId) throws JOSEException {
        Instant now = Instant.now();
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer())
                        .subject(customerId.toString())
                        .claim("customer_id", customerId.toString())
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .build();
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
