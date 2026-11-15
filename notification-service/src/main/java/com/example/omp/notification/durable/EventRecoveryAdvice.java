package com.example.omp.notification.durable;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = EventRecoveryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EventRecoveryAdvice {
    @ExceptionHandler({
        IllegalArgumentException.class,
        jakarta.validation.ConstraintViolationException.class,
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail invalid() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid event recovery request");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> status(
            ResponseStatusException failure) {
        var status = failure.getStatusCode();
        return org.springframework.http.ResponseEntity.status(status)
                .body(problem(status, "Event recovery request cannot be applied"));
    }

    private static ProblemDetail problem(
            org.springframework.http.HttpStatusCode status, String detail) {
        var result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setType(URI.create("urn:problem:event-recovery-" + status.value()));
        return result;
    }
}
