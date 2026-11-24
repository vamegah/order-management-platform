package com.example.omp.payment;

import static org.assertj.core.api.Assertions.*;

import com.example.omp.payment.web.ProviderWebhookNetworkFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ProviderWebhookNetworkFilterTest {
    @Test
    void forwardedHeadersNeverGrantAccessAndSignaturesRemainADownstreamRequirement()
            throws Exception {
        var metrics = new SimpleMeterRegistry();
        var filter = new ProviderWebhookNetworkFilter("203.0.113.1/32", metrics);
        var request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/stripe");
        request.setServletPath(request.getRequestURI());
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        assertThat(metrics.get("payment.webhook.rejections").counter().count()).isEqualTo(1);
        request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/stripe");
        request.setServletPath(request.getRequestURI());
        request.setRemoteAddr("203.0.113.1");
        chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
