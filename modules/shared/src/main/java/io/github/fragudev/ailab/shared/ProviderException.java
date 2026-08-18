package io.github.fragudev.ailab.shared;

/**
 * Base type for failures talking to an AI provider (chat or embedding). Translated to an RFC 9457
 * Problem Detail at the API edge — never allowed to surface as a bare 500 or a hung request.
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
