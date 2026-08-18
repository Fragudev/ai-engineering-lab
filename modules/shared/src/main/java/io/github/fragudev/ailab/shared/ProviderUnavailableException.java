package io.github.fragudev.ailab.shared;

/** A provider could not be reached at all (connection refused, DNS failure, ...). Maps to HTTP 502. */
public class ProviderUnavailableException extends ProviderException {

    public ProviderUnavailableException(String provider, Throwable cause) {
        super("Provider '%s' is unavailable".formatted(provider), cause);
    }
}
