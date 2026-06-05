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
import java.util.*;
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
 *   <li>{@code detect_build_tool} — detect which build tool(s) a project uses</li>
 * </ul>
 * The legacy {@code get_maven_version} and {@code execute_maven_command} tools
 * have been consolidated into the generic equivalents (specify {@code "maven"}
 * as the build tool name).
 */
@Service
public class BuildToolsService {

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final Pattern COMMAND_PATTERN =
            Pattern.compile("^(gradle\\w*\\s+)?[a-zA-Z0-9\\s._=/:@;\\-]+$");

    private final BuildToolProvider provider;

    public BuildToolsService(BuildToolProvider provider) {
        this.provider = provider;
    }

    /**
     * Get the version of any registered build tool.
     */
    @Tool(name = "get_build_tool_version",
          description = "Get the installed version of a build tool. Supports maven, gradle, sbt, and other registered tools.")
    public String getBuildToolVersion(
            @ToolParam(required = true, description = "Name of the build tool (e.g., 'maven', 'gradle', 'sbt')")
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
          description = "Execute a build command using Maven, Gradle, SBT, or any registered build tool. " +
                        "Auto-detects the build tool from project markers if no tool name is specified. " +
                        "Maven supports: clean, compile, test, package, install, deploy, validate. " +
                        "Gradle supports: clean, build, test, compileJava, compileTestJava, jar, assemble, check. " +
                        "SBT supports: compile, test, run, package, clean, assembly.")
    public String executeBuildCommand(
            @ToolParam(required = false, description = "Name of the build tool ('maven', 'gradle', or 'sbt'). Omit to auto-detect from project directory.")
            String buildToolName,
            @ToolParam(required = false, description = "Path to the build tool installation directory. Optional for Gradle and SBT (uses wrapper or PATH fallback).")
            String buildToolHome,
            @ToolParam(required = true, description = "Path to the project directory containing build files")
            String projectDir,
            @ToolParam(required = true, description = "Build command to execute (e.g., 'clean compile' for Maven, 'build' for Gradle, 'compile;test' for SBT)")
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

        // Canonicalize paths; buildToolHome is optional (Gradle/SBT uses wrapper/PATH)
        String validatedHome = null;
        if (buildToolHome != null && !buildToolHome.isBlank()) {
            try {
                validatedHome = Path.of(buildToolHome).toRealPath().toString();
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Cannot resolve build tool home: " + buildToolHome, e);
            }
        }
        Path validatedProject;
        try {
            validatedProject = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve path: " + e.getMessage(), e);
        }
        if (!Files.isDirectory(validatedProject)) {
            throw new IllegalArgumentException("Project directory is not valid: " + projectDir);
        }

        BuildTool tool = provider.resolve(buildToolName, validatedProject);
        return tool.executeCommand(validatedHome, validatedProject.toString(), command);
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

    /**
     * Detect which build tool(s) a project directory uses.
     * <p>
     * Scans the project directory for marker files (pom.xml, build.gradle,
     * build.sbt, etc.) and returns a structured JSON result showing which
     * build tools were detected. Includes information about wrapper
     * availability and project structure hints.
     * <p>
     * When multiple markers coexist (hybrid projects), all detected tools
     * are listed rather than silently picking one.
     */
    @Tool(name = "detect_build_tool",
          description = "Detect which build tool a project uses by scanning for " +
                        "marker files (pom.xml, build.gradle, build.sbt, etc.). " +
                        "Returns a JSON object with detected tools, matched marker files, " +
                        "wrapper availability, and project structure hints. " +
                        "Use this to understand a project's build system before executing commands.")
    public String detectBuildTool(
            @ToolParam(required = true,
                       description = "Path to the project directory to scan for build tool markers")
            String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return buildDetectionError("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return buildDetectionError("Project directory is not valid: " + projectDir);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectDir", dir.toString());
        result.put("status", "success");

        List<Map<String, Object>> detections = new ArrayList<>();
        Map<String, BuildTool> allTools = provider.getAllTools();

        for (Map.Entry<String, BuildTool> entry : allTools.entrySet()) {
            BuildTool tool = entry.getValue();
            Map<String, Object> detection = new LinkedHashMap<>();
            detection.put("tool", tool.getName());

            List<String> matchedFiles = new ArrayList<>();
            List<String> wrapperFiles = new ArrayList<>();
            List<String> hints = new ArrayList<>();

            switch (tool.getName()) {
                case "maven":
                    checkFile(dir, "pom.xml", matchedFiles);
                    checkWrapper(dir, "mvnw", wrapperFiles);
                    if (Files.exists(dir.resolve("pom.xml"))) {
                        hints.add("POM-based project");
                        if (Files.isDirectory(dir.resolve("src/main/java"))) {
                            hints.add("standard Maven layout detected");
                        }
                    }
                    break;
                case "gradle":
                    checkFile(dir, "build.gradle", matchedFiles);
                    checkFile(dir, "build.gradle.kts", matchedFiles);
                    checkFile(dir, "settings.gradle", matchedFiles);
                    checkFile(dir, "settings.gradle.kts", matchedFiles);
                    checkWrapper(dir, "gradlew", wrapperFiles);
                    if (Files.exists(dir.resolve("build.gradle.kts"))) {
                        hints.add("Kotlin DSL project");
                    } else if (Files.exists(dir.resolve("build.gradle"))) {
                        hints.add("Groovy DSL project");
                    }
                    if (Files.exists(dir.resolve("gradle/libs.versions.toml"))) {
                        hints.add("version catalog detected");
                    }
                    break;
                case "sbt":
                    checkFile(dir, "build.sbt", matchedFiles);
                    checkWrapper(dir, "sbt", wrapperFiles);
                    if (Files.exists(dir.resolve("build.sbt"))) {
                        hints.add("SBT project");
                        if (Files.exists(dir.resolve("project/build.properties"))) {
                            hints.add("SBT version pinned in build.properties");
                        }
                        if (Files.exists(dir.resolve("project/plugins.sbt"))) {
                            hints.add("SBT plugins configured");
                        }
                    }
                    break;
            }

            boolean detected = !matchedFiles.isEmpty();
            detection.put("detected", detected);
            if (detected) {
                detection.put("matchedFiles", matchedFiles);
                if (!wrapperFiles.isEmpty()) {
                    detection.put("wrappers", wrapperFiles);
                }
                if (!hints.isEmpty()) {
                    detection.put("hints", hints);
                }
            }

            detections.add(detection);
        }

        result.put("detections", detections);

        // Summary: which tools were detected?
        List<String> detectedTools = new ArrayList<>();
        for (Map<String, Object> d : detections) {
            if (Boolean.TRUE.equals(d.get("detected"))) {
                detectedTools.add((String) d.get("tool"));
            }
        }
        result.put("detectedTools", detectedTools);
        result.put("toolCount", detectedTools.size());

        if (detectedTools.isEmpty()) {
            result.put("warning", "No build tool markers found. The project directory may not be a recognized JVM project.");
        } else if (detectedTools.size() > 1) {
            result.put("warning", "Multiple build tools detected (hybrid project). Maven is prioritized for auto-detection when tool name is not specified.");
        }

        return jsonEncode(result);
    }

    // ─── Detection helpers ──────────────────────────────────────────────

    private void checkFile(Path dir, String filename, List<String> matched) {
        if (Files.exists(dir.resolve(filename))) {
            matched.add(filename);
        }
    }

    private void checkWrapper(Path dir, String wrapperName, List<String> wrappers) {
        if (Files.isExecutable(dir.resolve(wrapperName))) {
            wrappers.add(wrapperName + " wrapper available");
        }
    }

    private String buildDetectionError(String message) {
        return "{\"status\":\"error\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    // ─── JSON helpers (inline to avoid adding dependencies) ─────────────

    @SuppressWarnings("unchecked")
    static String jsonEncode(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"");
            sb.append(escapeJson(entry.getKey()));
            sb.append("\":");
            appendJsonValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    static void appendJsonValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append("\"").append(escapeJson((String) value)).append("\"");
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof List) {
            sb.append("[");
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                appendJsonValue(sb, list.get(i));
            }
            sb.append("]");
        } else if (value instanceof Map) {
            sb.append("{");
            Map<String, Object> map = (Map<String, Object>) value;
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                appendJsonValue(sb, entry.getValue());
            }
            sb.append("}");
        } else {
            sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
