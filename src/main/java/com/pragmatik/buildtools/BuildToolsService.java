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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Unified MCP service exposing build tool operations as MCP tools.
 * <p>
 * Delegates to {@link BuildToolProvider} for tool resolution, supporting
 * both explicit tool selection (by name) and auto-detection from project
 * directories.
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code get_build_tool_version} — version of any registered build tool</li>
 *   <li>{@code execute_build_command} — execute a build command with auto-detection</li>
 *   <li>{@code list_build_tools} — list all registered tools and their commands</li>
 * </ul>
 * The legacy {@code get_maven_version} and {@code execute_maven_command} tools
 * have been consolidated into the generic equivalents (specify {@code "maven"}
 * as the build tool name).
 */
@Service
public class BuildToolsService {

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final Pattern COMMAND_PATTERN =
            Pattern.compile("^(gradle\\\\w*\\\\s+)?[a-zA-Z0-9\\\\s._=/:@\\\\-]+$");

    private final BuildToolProvider provider;

    public BuildToolsService(BuildToolProvider provider) {
        this.provider = provider;
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
        // Validate command length and character content
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Command cannot be null or empty.");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException(
                    "Command too long (max " + MAX_COMMAND_LENGTH + " characters).");
        }
        if (!COMMAND_PATTERN.matcher(command).matches()) {
            throw new IllegalArgumentException("Command contains disallowed characters.");
        }

        // Canonicalize paths to prevent traversal attacks
        Path validatedHome;
        Path validatedProject;
        try {
            validatedHome = Path.of(buildToolHome).toRealPath();
            validatedProject = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve path: " + e.getMessage(), e);
        }
        if (!Files.isDirectory(validatedHome)) {
            throw new IllegalArgumentException("Build tool home is not a valid directory: " + buildToolHome);
        }
        if (!Files.isDirectory(validatedProject)) {
            throw new IllegalArgumentException("Project directory is not valid: " + projectDir);
        }

        BuildTool tool = provider.resolve(buildToolName, validatedProject);
        return tool.executeCommand(validatedHome.toString(), validatedProject.toString(), command);
    }

    /**
     * List all registered build tools with their supported commands.
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
