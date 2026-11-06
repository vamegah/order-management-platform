package com.example.omp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.omp.payment.web.WebhookBodyLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class WebhookBodyLimitFilterTest {
    @Test
    void rejectsOversizedChunkedBody() throws Exception {
        var request =
                new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/stripe") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        request.setServletPath("/api/v1/payments/webhooks/stripe");
        request.setContent(new byte[WebhookBodyLimitFilter.MAX_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new WebhookBodyLimitFilter().doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void preservesSignatureBytesExactly() throws Exception {
        byte[] raw = "{ \"id\" : \"evt-1\" }\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/stripe");
        request.setServletPath("/api/v1/payments/webhooks/stripe");
        request.setContent(raw);
        var response = new MockHttpServletResponse();
        new WebhookBodyLimitFilter()
                .doFilter(
                        request,
                        response,
                        (wrapped, result) ->
                                assertThat(wrapped.getInputStream().readAllBytes())
                                        .containsExactly(raw));
    }
}
