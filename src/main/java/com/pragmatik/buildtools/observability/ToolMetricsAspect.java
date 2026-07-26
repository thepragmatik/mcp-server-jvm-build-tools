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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Aspect that intercepts every {@code @Tool}-annotated method across all service classes and
 * records Micrometer metrics: call count, error count, and execution duration.
 *
 * <p>This avoids the need to add {@code @Timed} to every individual {@code @Tool} method — one
 * aspect covers all ~30 tools across the server.
 */
@Aspect
@Component
public class ToolMetricsAspect {

    private final MeterRegistry meterRegistry;

    public ToolMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Around advice for any public method annotated with {@link Tool @Tool}. Records the call
     * count, a timer for execution duration, and an error count on failure.
     */
    @Around("execution(@org.springframework.ai.tool.annotation.Tool * *(..))")
    public Object measureToolCall(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        String toolName =
                (toolAnnotation != null && !toolAnnotation.name().isBlank()) ? toolAnnotation.name() : method.getName();
        String serviceName = method.getDeclaringClass().getSimpleName();

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Object result = joinPoint.proceed();

            sample.stop(Timer.builder("buildtools.tool.duration")
                    .tag("tool_name", toolName)
                    .tag("service", serviceName)
                    .description("Tool execution duration")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry));

            Counter.builder("buildtools.tool.calls")
                    .tag("tool_name", toolName)
                    .tag("service", serviceName)
                    .tag("status", "success")
                    .description("Total tool invocations")
                    .register(meterRegistry)
                    .increment();

            return result;
        } catch (Throwable t) {
            sample.stop(Timer.builder("buildtools.tool.duration")
                    .tag("tool_name", toolName)
                    .tag("service", serviceName)
                    .description("Tool execution duration")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry));

            Counter.builder("buildtools.tool.calls")
                    .tag("tool_name", toolName)
                    .tag("service", serviceName)
                    .tag("status", "error")
                    .description("Total tool invocations")
                    .register(meterRegistry)
                    .increment();

            Counter.builder("buildtools.tool.errors")
                    .tag("tool_name", toolName)
                    .tag("service", serviceName)
                    .tag("error_type", classifyError(t))
                    .description("Tool error count")
                    .register(meterRegistry)
                    .increment();

            throw t;
        }
    }

    /**
     * Classifies a thrown exception into a broad error category for metric labels.
     */
    private static String classifyError(Throwable t) {
        if (t instanceof IllegalArgumentException) return "invalid_input";
        if (t instanceof IllegalStateException) return "state_error";
        if (t instanceof SecurityException) return "auth";
        if (t instanceof java.io.IOException) return "io";
        if (t instanceof java.util.concurrent.TimeoutException) return "timeout";
        return "unknown";
    }
}
