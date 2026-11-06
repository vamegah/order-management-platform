package com.example.omp.order.clients.grpc.interceptor;

import io.grpc.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * gRPC client interceptor that propagates the request ID (from SLF4J MDC) as gRPC metadata.
 *
 * <p>The request ID originates in order-service's HTTP filter (see shared-web's {@code
 * RequestIdFilter}); this interceptor carries it to downstream services so logs across services
 * share a correlation ID.
 */
@Component
public class RequestIdInterceptor implements ClientInterceptor {

    private static final String MDC_REQUEST_ID = "request_id";
    private static final Metadata.Key<String> X_REQUEST_ID =
            Metadata.Key.of("X-Request-Id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String requestId = MDC.get(MDC_REQUEST_ID);
                if (requestId != null && !requestId.isBlank()) {
                    headers.put(X_REQUEST_ID, requestId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
