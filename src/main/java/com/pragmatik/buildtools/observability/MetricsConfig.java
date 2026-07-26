/*
 *
 *  Copyright 2025 Rahul Thakur
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pragmatik.buildtools.observability;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * Central metrics configuration for the MCP server.
 *
 * <p>Registers the {@link TimedAspect} bean so that {@code @Timed} annotations on tool methods are
 * honoured, and customises the meter registry with common tags and filters. Also publishes the
 * number of registered MCP tools as a gauge.
 */
@Configuration
public class MetricsConfig {

    /**
     * The {@link TimedAspect} bean enables {@code @Timed} annotations on service methods. Without
     * this bean, the annotations are silently ignored.
     */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /**
     * Adds a global {@code application} tag to every metric and suppresses the
     * {@code spring.data.*} auto-metrics that are irrelevant for this server.
     */
    @Bean
    public MeterBinder metricsCommonTags() {
        return registry -> registry.config()
                .commonTags("application", "mcp-server-jvm-build-tools")
                .meterFilter(MeterFilter.denyNameStartsWith("spring.data."));
    }

    /**
     * Publishes a gauge reflecting the total number of registered MCP {@code @Tool} methods at
     * startup. Returns a {@link MeterBinder} that registers the gauge with the {@link MeterRegistry},
     * avoiding a direct {@link ToolCallbackProvider} bean reference in the return type so Spring
     * does not see two candidates when injecting {@code ToolCallbackProvider}.
     */
    @Bean
    public MeterBinder toolsRegisteredGauge(@Nullable ToolCallbackProvider provider) {
        return registry -> {
            if (provider != null) {
                registry.gauge("buildtools.tools.registered", provider, p -> p.getToolCallbacks().length);
            }
        };
    }
}
