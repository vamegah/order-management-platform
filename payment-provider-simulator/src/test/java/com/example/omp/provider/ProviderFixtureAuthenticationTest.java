package com.example.omp.provider;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;

class ProviderFixtureAuthenticationTest {
    final ProviderFixtureAuthentication filter =
            new ProviderFixtureAuthentication("sk_test_fixture");

    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setServletPath("/__test/controls");
        request.addHeader("Authorization", "Bearer sk_test_fixture");
        request.setContentType("application/json");
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong", "oversized"})
    void controlsRejectWrongOrExcessiveCredentials(String kind) throws Exception {
        var request = request();
        request.removeHeader("Authorization");
        if (kind.equals("oversized")) request.addHeader("Authorization", "x".repeat(513));
        else request.addHeader("Authorization", "Bearer wrong");
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(reached).isFalse();
    }

    @Test
    void chunkedBodyCannotBypassTheControlSizeLimit() throws Exception {
        var request =
                new MockHttpServletRequest() {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                };
        request.setServletPath("/__test/controls");
        request.addHeader("Authorization", "Bearer sk_test_fixture");
        request.setContentType("application/json");
        request.setContent(new byte[16385]);
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    throw new AssertionError("Oversized chunked controls reached the controller");
                });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void boundedJsonRemainsReadableByTheController() throws Exception {
        var request = request();
        request.setContent("{\"delayMs\":0}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {
                    var input = req.getInputStream();
                    assertThat(input.isReady()).isTrue();
                    assertThat(input.isFinished()).isFalse();
                    assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .isEqualTo("{\"delayMs\":0}");
                    assertThat(input.isFinished()).isTrue();
                });
        assertThatThrownBy(() -> new ProviderFixtureAuthentication("sk_live_forbidden"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
