package io.github.fragudev.ailab;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import io.github.fragudev.ailab.shared.ToolAuthorizationException;
import io.github.fragudev.ailab.shared.ToolTimeoutException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
        if (error instanceof ProviderTimeoutException || error instanceof ToolTimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (error instanceof ProviderUnavailableException) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (error instanceof ToolAuthorizationException) {
            return HttpStatus.FORBIDDEN;
        }
        if (error instanceof MaxUploadSizeExceededException) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
        }
        if (error instanceof NoSuchElementException) {
            return HttpStatus.NOT_FOUND;
        }
        if (error instanceof IllegalArgumentException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
