package io.github.fragudev.ailab.tools.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, UUID> {}
