package io.github.fragudev.ailab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root. Wires every domain module together and owns no domain logic of its own
 * (see docs/architecture.md #3). This class's package is the base package Spring Modulith scans
 * to discover module boundaries.
 */
@SpringBootApplication
public class AiEngineeringLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiEngineeringLabApplication.class, args);
    }
}
