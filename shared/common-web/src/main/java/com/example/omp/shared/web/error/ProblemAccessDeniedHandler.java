package com.example.omp.shared.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Matches MVC's problem contract when authorization rejects a request before a controller. */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
            throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Access denied\"}");
    }
}
