package io.github.fragudev.ailab;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * One place mapping typed exceptions from {@code shared} to RFC 9457 Problem Details, used both by
 * {@link ApiExceptionHandler} (synchronous failures) and the SSE `error` event (mid-stream
 * failures, which cannot become a real HTTP status — see openapi.yaml).
 */
final class ProblemDetails {

    private ProblemDetails() {}

    static ProblemDetail of(Throwable error) {
        return ProblemDetail.forStatusAndDetail(statusFor(error), error.getMessage());
    }

    static HttpStatus statusFor(Throwable error) {
        if (error instanceof ProviderTimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (error instanceof ProviderUnavailableException) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (error instanceof NoSuchElementException) {
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
