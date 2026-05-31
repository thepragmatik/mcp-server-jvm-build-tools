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

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Unified MCP service exposing build tool operations as MCP tools.
 * <p>
 * Delegates to {@link BuildToolProvider} for tool resolution, supporting
 * both explicit tool selection (by name) and auto-detection from project
 * directories. Maintains backward compatibility with existing MCP clients
 * via the {@code get_maven_version} and {@code execute_maven_command} tools.
 */
@Service
public class BuildToolsService {

    private final BuildToolProvider provider;

    public BuildToolsService(BuildToolProvider provider) {
        this.provider = provider;
    }

    /**
     * Get the installed Maven version.
     * Maintains backward compatibility with existing MCP clients.
     */
    @Tool(name = "get_maven_version", description = "Gets the version for Apache Maven")
    public String getMavenVersion() {
        return provider.getTool("maven")
                .orElseThrow(() -> new IllegalStateException("Maven build tool not registered"))
                .version();
    }

    /**
     * Execute a Maven build command.
     * Maintains backward compatibility with existing MCP clients.
     */
    @Tool(name = "execute_maven_command",
          description = "Execute a Maven build command in a specified project directory. " +
                        "Supports: clean, compile, test, package, install, deploy, validate.")
    public String executeMavenCommand(
            @ToolParam(required = true, description = "Path to the Maven installation directory (e.g., /usr/share/maven)")
            String mavenHome,
            @ToolParam(required = true, description = "Path to the Maven project directory containing pom.xml")
            String projectDir,
            @ToolParam(required = true, description = "Maven command to execute (e.g., 'clean compile', 'test', 'package'). Can optionally include 'mvn' prefix.")
            String command) {
        BuildTool maven = provider.getTool("maven")
                .orElseThrow(() -> new IllegalStateException("Maven build tool not registered"));
        return maven.executeCommand(mavenHome, projectDir, command);
    }

    /**
     * Get the version of any registered build tool.
     */
    @Tool(name = "get_build_tool_version",
          description = "Get the installed version of a build tool. Supports maven, gradle, and other registered tools.")
    public String getBuildToolVersion(
            @ToolParam(required = true, description = "Name of the build tool (e.g., 'maven', 'gradle')")
            String buildToolName) {
        BuildTool tool = provider.getTool(buildToolName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown build tool: " + buildToolName +
                        ". Available: " + provider.getAllTools().keySet()));
        return tool.version();
    }

    /**
     * Execute a build command using any registered build tool.
     * Auto-detects the tool from the project directory if no tool name is specified.
     */
    @Tool(name = "execute_build_command",
          description = "Execute a build command using Maven, Gradle, or any registered build tool. " +
                        "Auto-detects the build tool from project markers if no tool name is specified. " +
                        "Maven supports: clean, compile, test, package, install, deploy, validate. " +
                        "Gradle supports: clean, build, test, compileJava, compileTestJava, jar, assemble, check.")
    public String executeBuildCommand(
            @ToolParam(required = false, description = "Name of the build tool ('maven' or 'gradle'). Omit to auto-detect from project directory.")
            String buildToolName,
            @ToolParam(required = true, description = "Path to the build tool installation directory")
            String buildToolHome,
            @ToolParam(required = true, description = "Path to the project directory containing build files")
            String projectDir,
            @ToolParam(required = true, description = "Build command to execute (e.g., 'clean compile' for Maven, 'build' for Gradle)")
            String command) {
        Path projectPath = Path.of(projectDir);
        BuildTool tool = provider.resolve(buildToolName, projectPath);
        return tool.executeCommand(buildToolHome, projectDir, command);
    }

    /**
     * List all registered build tools.
     */
    @Tool(name = "list_build_tools",
          description = "List all registered build tools with their supported commands.")
    public String listBuildTools() {
        return provider.getAllTools().entrySet().stream()
                .map(entry -> {
                    BuildTool tool = entry.getValue();
                    return tool.getName() + ": " + String.join(", ", tool.getSupportedCommands());
                })
                .collect(Collectors.joining("\n"));
    }
}
