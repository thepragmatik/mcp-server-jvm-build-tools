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
package com.pragmatik.buildtools;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Health indicator that reports which build tools are available on the system PATH.
 * <p>
 * Useful for monitoring in Docker/cloud deployments where tool availability
 * is critical to the MCP server's functionality.
 */
@Component
public class BuildToolsHealthIndicator implements HealthIndicator {

    private static final List<String> SUPPORTED_TOOLS = Arrays.asList("mvn", "gradle", "sbt");

    @Override
    public Health health() {
        var builder = Health.up();

        List<String> available = SUPPORTED_TOOLS.stream()
                .filter(this::isOnPath)
                .collect(Collectors.toList());

        List<String> missing = SUPPORTED_TOOLS.stream()
                .filter(t -> !available.contains(t))
                .collect(Collectors.toList());

        builder.withDetail("availableTools", available);
        builder.withDetail("totalSupported", SUPPORTED_TOOLS.size());
        builder.withDetail("availableCount", available.size());

        if (!missing.isEmpty()) {
            builder.withDetail("missingTools", missing);
        }

        if (available.isEmpty()) {
            builder.down()
                   .withDetail("warning", "No build tools found on PATH — server can start but tools will not function");
        }

        return builder.build();
    }

    private boolean isOnPath(String tool) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", tool);
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
