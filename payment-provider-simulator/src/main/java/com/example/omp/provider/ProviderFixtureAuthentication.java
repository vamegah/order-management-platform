package com.example.omp.provider;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ProviderFixtureAuthentication extends OncePerRequestFilter {
    private final byte[] expected;

    public ProviderFixtureAuthentication(
            @Value("${omp.simulator.api-key:sk_test_omp_simulator}") String key) {
        if (!key.startsWith("sk_test_") || key.length() > 256)
            throw new IllegalArgumentException("Simulator accepts only test credentials");
        expected = ("Bearer " + key).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getServletPath().startsWith("/v1/")
                || request.getServletPath().startsWith("/__test/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null
                || header.length() > 512
                || !MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401);
            return;
        }
        if (request.getContentLengthLong() > 16384) {
            response.sendError(413);
            return;
        }
        if (request.getContentType() != null
                && request.getContentType()
                        .toLowerCase(java.util.Locale.ROOT)
                        .startsWith("application/json")) {
            byte[] body = request.getInputStream().readNBytes(16385);
            if (body.length > 16384) {
                response.sendError(413);
                return;
            }
            var bounded =
                    new HttpServletRequestWrapper(request) {
                        @Override
                        public ServletInputStream getInputStream() {
                            var input = new java.io.ByteArrayInputStream(body);
                            return new ServletInputStream() {
                                public int read() {
                                    return input.read();
                                }

                                public boolean isFinished() {
                                    return input.available() == 0;
                                }

                                public boolean isReady() {
                                    return true;
                                }

                                public void setReadListener(ReadListener listener) {
                                    throw new UnsupportedOperationException();
                                }
                            };
                        }
                    };
            chain.doFilter(bounded, response);
        } else chain.doFilter(request, response);
    }
}
