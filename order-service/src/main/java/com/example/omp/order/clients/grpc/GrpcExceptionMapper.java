package com.example.omp.order.clients.grpc;

import com.example.omp.order.error.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps gRPC status codes to the order-service's domain exceptions.
 *
 * <p>Same role as the {@code FeignErrorDecoder} from Phase 1, but for gRPC. Centralizes the mapping
 * so every client wrapper uses the same rules.
 *
 * <p><b>Design principle:</b> the mapping is one-way. Downstream services never see order-service's
 * domain exceptions; they produce gRPC statuses. Order-service never sees downstream domain
 * exceptions; it produces its own. The mapping bridges the two vocabularies.
 */
@Component
public class GrpcExceptionMapper {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionMapper.class);

    public RuntimeException map(StatusRuntimeException e, String downstreamService) {
        Status.Code code = e.getStatus().getCode();
        String description = e.getStatus().getDescription();
        String message =
                description != null
                        ? "Downstream '" + downstreamService + "': " + description
                        : "Downstream '" + downstreamService + "' returned " + code;

        log.debug("Mapping gRPC status {} from {} to domain exception", code, downstreamService);

        return switch (code) {
            case NOT_FOUND -> new ProductUnavailableException(message);
            case FAILED_PRECONDITION -> new InsufficientStockException(message);
            case INVALID_ARGUMENT -> new InvalidOrderDataException(message);
            case ALREADY_EXISTS -> new InvalidOrderDataException(message);
            case UNAUTHENTICATED -> new DownstreamAuthenticationException(message);
            case PERMISSION_DENIED -> new OrderAccessDeniedException(message);
            case UNAVAILABLE -> new DownstreamServiceException(downstreamService, 503, message);
            case DEADLINE_EXCEEDED ->
                    new DownstreamServiceException(
                            downstreamService, 504, "Call to " + downstreamService + " timed out");
            case RESOURCE_EXHAUSTED ->
                    new DownstreamServiceException(downstreamService, 429, message);
            default -> new DownstreamServiceException(downstreamService, 500, message);
        };
    }
}
