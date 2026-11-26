package com.example.omp.shared.web.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after Spring Security, using verified iss/sub only. Forwarding and identity headers are
 * ignored.
 */
public final class QuotaFilter extends OncePerRequestFilter {
    private final QuotaService quotas;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuotaFilter(QuotaService quotas) {
        this.quotas = quotas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "OPTIONS".equals(request.getMethod())
                || !(path.startsWith("/api/")
                        || path.equals("/graphql")
                        || path.equals("/ws")
                        || path.startsWith("/ws/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var token = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken() : null;
        QuotaPolicy policy = policy(request);
        if (token != null
                && (policy == QuotaPolicy.READ
                        || policy == QuotaPolicy.WRITE
                        || policy == QuotaPolicy.EXPENSIVE)
                && auth.getAuthorities().stream()
                        .anyMatch(
                                a ->
                                        a.getAuthority().equals("ROLE_admin")
                                                || a.getAuthority().equals("ROLE_ADMIN"))) {
            // Administrative recovery has a separate finite budget; identity and permission still
            // come from the verified JWT.
            policy = QuotaPolicy.OPERATOR;
        }
        var decision =
                quotas.acquire(
                        policy,
                        token == null ? null : token.getClaimAsString("iss"),
                        token == null ? null : token.getSubject());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        // 503 callbacks are deliberately retryable by providers; they never acknowledge a lost
        // receipt.
        int status = !decision.available() || policy == QuotaPolicy.WEBHOOK ? 503 : 429;
        String code = decision.available() ? "RATE_LIMITED" : "QUOTA_UNAVAILABLE";
        response.setStatus(status);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setHeader("Cache-Control", "no-store");
        if (policy == QuotaPolicy.GRAPHQL) {
            response.setContentType("application/graphql-response+json");
            mapper.writeValue(
                    response.getOutputStream(),
                    Map.of(
                            "errors",
                            List.of(
                                    Map.of(
                                            "message",
                                            "Request quota exceeded or temporarily unavailable",
                                            "extensions",
                                            Map.of(
                                                    "code",
                                                    code,
                                                    "retryAfterSeconds",
                                                    decision.retryAfterSeconds())))));
        } else {
            response.setContentType("application/problem+json");
            mapper.writeValue(
                    response.getOutputStream(),
                    Map.of(
                            "type",
                            "urn:omp:problem:" + code.toLowerCase(java.util.Locale.ROOT),
                            "title",
                            status == 429 ? "Too Many Requests" : "Service Unavailable",
                            "status",
                            status,
                            "detail",
                            "Request quota exceeded or temporarily unavailable",
                            "code",
                            code));
        }
    }

    static QuotaPolicy policy(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.equals("/graphql")) return QuotaPolicy.GRAPHQL;
        if (path.equals("/ws") || path.startsWith("/ws/")) return QuotaPolicy.HANDSHAKE;
        if (path.startsWith("/api/v1/payments/webhooks/")) return QuotaPolicy.WEBHOOK;
        if (path.contains("/admin/")
                || path.contains("/replay")
                || path.contains("/census")
                || path.contains("/repair")
                || path.contains("/refund")
                || path.contains("/checkout")
                || path.contains("/transfers")) return QuotaPolicy.EXPENSIVE;
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                ? QuotaPolicy.READ
                : QuotaPolicy.WRITE;
    }
}
