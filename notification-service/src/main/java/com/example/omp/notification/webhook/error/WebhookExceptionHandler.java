package com.example.omp.notification.webhook.error;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable, redacted problem responses for the public webhook management API. */
@RestControllerAdvice(basePackages = "com.example.omp.notification.webhook")
public class WebhookExceptionHandler {
    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        WebhookExceptions.InvalidSubscription.class,
        IllegalArgumentException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid webhook management request");
    }

    @ExceptionHandler({
        WebhookExceptions.SubscriptionNotFound.class,
        WebhookExceptions.DlqEntryNotFound.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "Webhook resource not found");
    }

    @ExceptionHandler({
        WebhookExceptions.SubscriptionDisabled.class,
        WebhookExceptions.DlqAlreadyReplayed.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail conflict() {
        return problem(HttpStatus.CONFLICT, "Webhook operation conflicts with the current state");
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:problem:webhook-" + status.value()));
        return problem;
    }
}
