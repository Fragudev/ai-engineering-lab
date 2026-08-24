package io.github.fragudev.ailab.workflow.internal;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs every submitted task synchronously on the calling thread — so a test can call {@link
 * WorkflowResumer#resumeAll()} and assert on its effects immediately, with no waiting or polling
 * for a background thread. Not a real thread pool; only fit for tests. */
class DirectExecutorService extends AbstractExecutorService {

    private boolean shutdown = false;

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }
}
