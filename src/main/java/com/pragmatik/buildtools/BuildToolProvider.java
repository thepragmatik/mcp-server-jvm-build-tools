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

import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Registry and auto-detection provider for {@link BuildTool} implementations.
 * <p>
 * Maintains an ordered map of registered build tools. New tools are registered
 * via the constructor. Auto-detection iterates the registry in insertion order,
 * calling {@link BuildTool#isProject(Path)} on each tool until a match is found.
 * Falls back to Maven if no marker files are detected (backward compatibility).
 */
@Component
public class BuildToolProvider {

    private final Map<String, BuildTool> registry;

    public BuildToolProvider() {
        this.registry = new LinkedHashMap<>();
        register(new MavenBuildTool());
        register(new GradleBuildTool());
        register(new SbtBuildTool());
        // Future: register(new BazelBuildTool());
    }

    /**
     * Register a build tool in the provider.
     * Tools registered later appear after earlier ones in auto-detection order.
     */
    public void register(BuildTool tool) {
        registry.put(tool.getName().toLowerCase(), tool);
    }

    /**
     * Retrieve a build tool by name.
     *
     * @param name the build tool name (case-insensitive, e.g., "maven", "gradle")
     * @return the BuildTool instance, or empty Optional if not found
     */
    public Optional<BuildTool> getTool(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.get(name.toLowerCase()));
    }

    /**
     * Resolve the appropriate build tool for a project.
     * <p>
     * If a specific tool name is provided, returns that tool directly.
     * Otherwise auto-detects by checking project markers for each registered
     * tool in insertion order. Falls back to Maven if no markers match.
     *
     * @param name       optional build tool name (null for auto-detect)
     * @param projectDir project directory to inspect for markers (only used for auto-detect)
     * @return the resolved BuildTool
     * @throws IllegalArgumentException if the named tool is not registered
     */
    public BuildTool resolve(String name, Path projectDir) {
        if (name != null && !name.isBlank()) {
            return getTool(name)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown build tool: " + name + ". Registered tools: " + registry.keySet()));
        }
        // Auto-detect from project markers
        if (projectDir != null) {
            for (BuildTool tool : registry.values()) {
                if (tool.isProject(projectDir)) {
                    return tool;
                }
            }
        }
        // Fallback to Maven for backward compatibility
        return registry.get("maven");
    }

    /**
     * Returns all registered build tools.
     */
    public Map<String, BuildTool> getAllTools() {
        return Collections.unmodifiableMap(registry);
    }

    /**
     * Returns the number of registered build tools.
     */
    public int size() {
        return registry.size();
    }
}
