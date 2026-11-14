package com.example.omp.shared.web.error;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

class ProblemAccessDeniedHandlerTest {
    @Test
    void filterDenialIsAProblemWithoutCredentialOrInternalExceptionDetails() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer private-credential");
        var response = new MockHttpServletResponse();
        new ProblemAccessDeniedHandler()
                .handle(
                        request,
                        response,
                        new AccessDeniedException("private internal authorization failure"));
        var problem = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(problem.get("status").intValue()).isEqualTo(403);
        assertThat(problem.get("detail").textValue()).isEqualTo("Access denied");
        assertThat(response.getContentAsString()).doesNotContain("credential", "internal");
    }

    @Test
    void aCommittedResponseIsNeverCorruptedByALaterDenial() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(204);
        response.flushBuffer();
        new ProblemAccessDeniedHandler()
                .handle(
                        new MockHttpServletRequest(),
                        response,
                        new AccessDeniedException("late failure"));
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
