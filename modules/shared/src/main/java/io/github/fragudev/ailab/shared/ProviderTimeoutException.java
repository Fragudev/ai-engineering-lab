package io.github.fragudev.ailab.shared;

import java.time.Duration;

/** A provider call did not complete within its configured timeout. Maps to HTTP 504. */
public class ProviderTimeoutException extends ProviderException {

    public ProviderTimeoutException(String provider, Duration timeout) {
        super("Provider '%s' did not respond within %s".formatted(provider, timeout));
    }
}
