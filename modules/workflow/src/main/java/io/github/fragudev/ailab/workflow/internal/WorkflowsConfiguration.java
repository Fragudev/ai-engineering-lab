package io.github.fragudev.ailab.workflow.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WorkflowsProperties.class)
class WorkflowsConfiguration {

    /** Unbounded virtual threads (Java 25) — both the top-level {@code engine.run(...)} call and its
     * own internal fan-out (parallel retrieval, parallel extraction) submit to this same executor;
     * unlike a bounded platform-thread pool, nesting submissions here can't deadlock the pool. */
    @Bean(destroyMethod = "close")
    ExecutorService workflowExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
