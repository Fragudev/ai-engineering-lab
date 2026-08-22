package io.github.fragudev.ailab;

import io.github.fragudev.ailab.shared.ProviderException;
import io.github.fragudev.ailab.shared.ToolAuthorizationException;
import io.github.fragudev.ailab.shared.ToolTimeoutException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every exception that reaches here becomes an RFC 9457 Problem Detail, never a bare 500 with a
 * stack trace (AGENTS.md, Conventions). Only covers synchronous failures — see
 * {@link ConversationController} and openapi.yaml for why a mid-stream SSE failure is handled
 * differently, in-band.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException e) {
        return respond(e);
    }

    @ExceptionHandler(ProviderException.class)
    ResponseEntity<ProblemDetail> handleProviderException(ProviderException e) {
        return respond(e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        return respond(e);
    }

    @ExceptionHandler(ToolAuthorizationException.class)
    ResponseEntity<ProblemDetail> handleToolAuthorization(ToolAuthorizationException e) {
        return respond(e);
    }

    @ExceptionHandler(ToolTimeoutException.class)
    ResponseEntity<ProblemDetail> handleToolTimeout(ToolTimeoutException e) {
        return respond(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled exception reached the API edge", e);
        return respond(e);
    }

    private static ResponseEntity<ProblemDetail> respond(Exception e) {
        ProblemDetail problem = ProblemDetails.of(e);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
