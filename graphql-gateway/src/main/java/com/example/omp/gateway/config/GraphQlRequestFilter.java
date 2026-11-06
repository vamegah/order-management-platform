package com.example.omp.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds HTTP bodies and per-identity requests before GraphQL parsing or downstream calls. */
@Component
public class GraphQlRequestFilter extends OncePerRequestFilter {
    private record Window(long started, int count) {}

    private final Map<String, Window> windows = new HashMap<>();
    private long nextCleanup;
    private final GraphQlProtectionProperties limits;
    private final ObjectMapper mapper;
    private final String path;
    private final Clock clock = Clock.systemUTC();

    public GraphQlRequestFilter(
            GraphQlProtectionProperties limits,
            ObjectMapper mapper,
            @Value("${spring.graphql.path:/graphql}") String path) {
        this.limits = limits;
        this.mapper = mapper;
        this.path = path;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String identity =
                authentication instanceof JwtAuthenticationToken token
                        ? token.getToken().getClaimAsString("iss") + ":" + token.getName()
                        : authentication != null
                                ? authentication.getName()
                                : request.getRemoteAddr();
        if (!allow(identity)) {
            response.setHeader(
                    "Retry-After", Long.toString(Math.max(1, limits.window().toSeconds())));
            reject(response, 429, "RATE_LIMITED", "GraphQL request rate exceeded");
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limits.maxRequestBytes()) {
            reject(response, 413, "REQUEST_TOO_LARGE", "GraphQL request body exceeds the limit");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limits.maxRequestBytes() + 1);
        if (body.length > limits.maxRequestBytes()) {
            reject(response, 413, "REQUEST_TOO_LARGE", "GraphQL request body exceeds the limit");
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        var input = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                return input.read();
                            }

                            @Override
                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException(
                                        "GraphQL MVC uses blocking body reads");
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(
                                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                },
                response);
    }

    private synchronized boolean allow(String identity) {
        long now = clock.millis();
        Window previous = windows.get(identity);
        if (previous != null && now - previous.started() < limits.window().toMillis()) {
            if (previous.count() >= limits.requestsPerWindow()) return false;
            windows.put(identity, new Window(previous.started(), previous.count() + 1));
            return true;
        }
        if (previous == null && windows.size() >= limits.maxIdentities()) {
            // Avoid scanning the entire bounded cache for every rejected new identity.
            if (now >= nextCleanup) {
                windows.values()
                        .removeIf(window -> now - window.started() >= limits.window().toMillis());
                nextCleanup = now + Math.min(1000, limits.window().toMillis());
            }
            if (windows.size() >= limits.maxIdentities()) return false;
        }
        windows.put(identity, new Window(now, 1));
        return true;
    }

    private void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/graphql-response+json");
        mapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "errors",
                        List.of(Map.of("message", message, "extensions", Map.of("code", code)))));
    }
}
