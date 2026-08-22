package io.github.fragudev.ailab.tools;

import io.github.fragudev.ailab.tools.internal.PendingConfirmationRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Public façade over {@code internal.PendingConfirmationRegistry} — the only thing
 * {@code POST /api/v1/tool-calls/{callId}:confirm} (app) needs to call. */
@Service
public class ToolConfirmationService {

    private final PendingConfirmationRegistry registry;

    public ToolConfirmationService(PendingConfirmationRegistry registry) {
        this.registry = registry;
    }

    /** @return {@code false} if {@code callId} is unknown, already resolved, or already timed out */
    public boolean confirm(UUID callId, boolean approved) {
        return registry.resolve(callId, approved);
    }
}
