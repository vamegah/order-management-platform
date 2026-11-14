package com.example.omp.notification.webhook;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.omp.notification.webhook.dlq.DlqService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class WebhookReplayContractTest {
    @Test
    void replayUsesTheExistingDlqServiceAndReturnsAnAsynchronousReceipt() {
        var dlq = mock(DlqService.class);
        var controller =
                new WebhookDeliveryController(
                        mock(WebhookDeliveryAttemptRepository.class),
                        mock(WebhookSubscriptionService.class),
                        mock(SubscriptionOwnerResolver.class),
                        dlq);
        var id = UUID.randomUUID();
        var token =
                Jwt.withTokenValue("unit-token").header("alg", "RS256").subject("operator").build();
        var response = controller.replay(token, id);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody())
                .isEqualTo(new WebhookDeliveryController.ReplayResponse(id, "queued"));
        verify(dlq).replay(id, "operator");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 409})
    void replayErrorsAreTypedAndRedactedAtTheRealHttpRoute(int status) throws Exception {
        var id = UUID.randomUUID();
        var dlq = mock(DlqService.class);
        RuntimeException failure =
                status == 404
                        ? new com.example.omp.notification.webhook.error.WebhookExceptions
                                .DlqEntryNotFound(id)
                        : new com.example.omp.notification.webhook.error.WebhookExceptions
                                .DlqAlreadyReplayed(id);
        doThrow(failure).when(dlq).replay(id, "operator");
        mvc(dlq).perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/webhook-deliveries/{id}/replay", id))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .is(status))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .contentTypeCompatibleWith("application/problem+json"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.status")
                                .value(status))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.type")
                                .value("urn:problem:webhook-" + status))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.detail")
                                .value(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        id.toString()))));
    }

    @Test
    void malformedIdentityIsAClientProblemBeforeReplay() throws Exception {
        var dlq = mock(DlqService.class);
        mvc(dlq).perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/webhook-deliveries/not-a-uuid/replay"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.status")
                                .value(400));
        verifyNoInteractions(dlq);
    }

    private org.springframework.test.web.servlet.MockMvc mvc(DlqService dlq) {
        var token =
                Jwt.withTokenValue("unit-token").header("alg", "RS256").subject("operator").build();
        var resolver =
                new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(
                            org.springframework.core.MethodParameter parameter) {
                        return parameter.getParameterType() == Jwt.class;
                    }

                    public Object resolveArgument(
                            org.springframework.core.MethodParameter parameter,
                            org.springframework.web.method.support.ModelAndViewContainer container,
                            org.springframework.web.context.request.NativeWebRequest request,
                            org.springframework.web.bind.support.WebDataBinderFactory binder) {
                        return token;
                    }
                };
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new WebhookDeliveryController(
                                mock(WebhookDeliveryAttemptRepository.class),
                                mock(WebhookSubscriptionService.class),
                                mock(SubscriptionOwnerResolver.class),
                                dlq))
                .setCustomArgumentResolvers(resolver)
                .setControllerAdvice(
                        new com.example.omp.notification.webhook.error.WebhookExceptionHandler())
                .build();
    }
}
