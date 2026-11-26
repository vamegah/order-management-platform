package com.example.omp.notification.websocket;

import com.example.omp.shared.web.quota.QuotaPolicy;
import com.example.omp.shared.web.quota.QuotaService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/** Shared authenticated quotas run between authentication and expensive ownership lookups. */
@Component
public final class StompQuotaInterceptor implements ChannelInterceptor {
    public static final class Rejected extends RuntimeException {
        private final String code;
        private final long retryAfterSeconds;

        Rejected(QuotaService.Decision decision) {
            super("STOMP quota rejected");
            code = decision.available() ? "RATE_LIMITED" : "QUOTA_UNAVAILABLE";
            retryAfterSeconds = decision.retryAfterSeconds();
        }

        public String code() {
            return code;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final QuotaService quotas;

    public StompQuotaInterceptor(QuotaService quotas) {
        this.quotas = quotas;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || !(headers.getUser() instanceof JwtPrincipal principal))
            return message;
        QuotaPolicy policy;
        if (headers.getCommand() == StompCommand.CONNECT) policy = QuotaPolicy.CONNECT;
        else if (headers.getCommand() == StompCommand.SUBSCRIBE) policy = QuotaPolicy.SUBSCRIBE;
        else return message;
        if (principal.isAdmin()) policy = QuotaPolicy.OPERATOR_STREAM;
        var attributes = headers.getSessionAttributes();
        String issuer =
                attributes != null && attributes.get("quotaIssuer") instanceof String value
                        ? value
                        : null;
        var decision = quotas.acquire(policy, issuer, principal.sub());
        if (!decision.allowed()) throw new Rejected(decision);
        return message;
    }
}
