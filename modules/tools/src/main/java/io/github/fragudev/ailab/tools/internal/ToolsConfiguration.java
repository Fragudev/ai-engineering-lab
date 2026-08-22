package io.github.fragudev.ailab.tools.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ToolsProperties.class)
class ToolsConfiguration {}
