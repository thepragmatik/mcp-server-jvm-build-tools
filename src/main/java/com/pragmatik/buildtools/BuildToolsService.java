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

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
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
 *   <li>{@code analyze_build_output} — execute a build and return structured JSON output</li>
 *   <li>{@code validate_build_configuration} — validate build files before execution</li>
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
    private final Map<String, BuildOutputParser> outputParsers;

    public BuildToolsService(BuildToolProvider provider) {
        this.provider = provider;
        this.outputParsers = new LinkedHashMap<>();
        this.outputParsers.put("maven", new MavenOutputParser());
        this.outputParsers.put("gradle", new GradleOutputParser());
        this.outputParsers.put("sbt", new SbtOutputParser());
    }

    /**
     * Get the version of any registered build tool.
     */
    @Tool(name = "get_build_tool_version",
          description = "Get the installed version of a build tool. Supports maven, gradle, sbt, and other registered tools.")
    public String getBuildToolVersion(
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
            @ToolParam(required = true, description = "Name of the build tool ('maven', 'gradle', 'sbt')")
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
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
            @ToolParam(required = false, description = "Name of the build tool ('maven', 'gradle', or 'sbt'). Omit to auto-detect from project directory.")
            String buildToolName,
            @ToolParam(required = false, description = "Path to the build tool installation directory. Optional for Gradle (uses wrapper or PATH fallback).")
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

        // Canonicalize paths; buildToolHome is optional (Gradle uses wrapper/PATH)
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
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
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

        return JsonUtils.toJson(result);
    }

    /**
     * Analyze the raw output of a build command and return structured JSON results.
     * <p>
     * Instead of returning raw text, this tool parses the build output (Maven or
     * Gradle) into a structured JSON object with success status, test summaries,
     * compile errors, warnings, and duration. This is far more usable for LLMs
     * than raw shell output.
     * <p>
     * The build is actually executed — this is not a dry-run. The tool runs the
     * same command as {@link #executeBuildCommand(String, String, String, String)}
     * and then parses the output.
     */
    @Tool(name = "analyze_build_output",
          description = "Execute a build command and return structured JSON output with parsed test " +
                        "results, compile errors, and warnings instead of raw text. Supports Maven, Gradle, and SBT. " +
                        "Returns: {success, tool, command, duration, testSummary: {total, passed, failed, " +
                        "errors, skipped}, errors: [{file, line, severity, message}], warnings, errorCount, " +
                        "warningCount}. Much easier for agents to process than raw build output.")
    public String analyzeBuildOutput(
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
            @ToolParam(required = false,
                       description = "Name of the build tool ('maven', 'gradle', or 'sbt'). Omit to auto-detect from project directory.")
            String buildToolName,
            @ToolParam(required = false,
                       description = "Path to the build tool installation directory. Optional for Gradle (uses wrapper or PATH fallback).")
            String buildToolHome,
            @ToolParam(required = true,
                       description = "Path to the project directory containing build files")
            String projectDir,
            @ToolParam(required = true,
                       description = "Build command to execute (e.g., 'clean test' for Maven, 'test' for Gradle)")
            String command) {

        // Canonicalize paths
        String validatedHome = null;
        if (buildToolHome != null && !buildToolHome.isBlank()) {
            try {
                validatedHome = Path.of(buildToolHome).toRealPath().toString();
            } catch (IOException e) {
                return JsonUtils.errorJson("Cannot resolve build tool home: " + buildToolHome);
            }
        }
        Path validatedProject;
        try {
            validatedProject = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(validatedProject)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        // Resolve the build tool
        BuildTool tool;
        try {
            tool = provider.resolve(buildToolName, validatedProject);
        } catch (IllegalArgumentException e) {
            return JsonUtils.errorJson(e.getMessage());
        }

        // Execute the build and capture output
        String rawOutput;
        int exitCode;
        try {
            rawOutput = tool.executeCommand(validatedHome, validatedProject.toString(), command);
            exitCode = 0;
        } catch (RuntimeException e) {
            rawOutput = e.getMessage();
            exitCode = 1;
        }

        // Parse output using the appropriate parser
        BuildOutputParser parser = outputParsers.getOrDefault(tool.getName(), outputParsers.get("maven"));
        Map<String, Object> result = parser.parse(rawOutput, exitCode, command);

        return JsonUtils.toJson(result);
    }

    /**
     * Validate a project's build configuration files for correctness.
     * <p>
     * Checks pom.xml, build.gradle, and build.gradle.kts for syntax errors,
     * required elements, and consistency issues. Returns a structured validation
     * report that LLMs can use to diagnose and fix build problems.
     * <p>
     * This is a static analysis tool — it does NOT execute the build, so it is
     * fast and safe. Use before {@link #executeBuildCommand} to catch issues early.
     */
    @Tool(name = "validate_build_configuration",
          description = "Validate build configuration files (pom.xml, build.gradle, build.gradle.kts) " +
                        "for correctness. Checks XML well-formedness, required elements, plugin version " +
                        "consistency for Maven, and basic syntax for Gradle. Returns structured JSON with " +
                        "{valid, tool, file, issues: [{severity, path, line, message, suggestion}]}. " +
                        "Use this before executing builds to catch configuration errors early.")
    public String validateBuildConfiguration(
            @ToolParam(required = true,
                       description = "Path to the project directory containing build files")
            String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        List<Map<String, Object>> allIssues = new ArrayList<>();
        String detectedTool = null;

        // Validate pom.xml if present
        Path pomXml = dir.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            detectedTool = "maven";
            List<Map<String, Object>> issues = validatePomXml(pomXml);
            allIssues.addAll(issues);
        }

        // Validate build.gradle if present
        Path buildGradle = dir.resolve("build.gradle");
        if (Files.exists(buildGradle)) {
            if (detectedTool == null) detectedTool = "gradle";
            List<Map<String, Object>> issues = validateBuildGradle(buildGradle, false);
            allIssues.addAll(issues);
        }

        // Validate build.gradle.kts if present
        Path buildGradleKts = dir.resolve("build.gradle.kts");
        if (Files.exists(buildGradleKts)) {
            if (detectedTool == null) detectedTool = "gradle";
            List<Map<String, Object>> issues = validateBuildGradle(buildGradleKts, true);
            allIssues.addAll(issues);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", allIssues.stream().noneMatch(i -> "ERROR".equals(i.get("severity"))));
        result.put("tool", detectedTool);
        result.put("projectDir", dir.toString());

        if (detectedTool != null) {
            result.put("file", "maven".equals(detectedTool) ? "pom.xml" : "build.gradle");
        }

        result.put("issueCount", allIssues.size());
        result.put("issues", allIssues);

        return JsonUtils.toJson(result);
    }

    // ─── Build output analysis ──────────────────────────────────────────

    /**
     * Check if a file exists in the given directory and add it to the matched files list.
     */
    private void checkFile(Path dir, String filename, List<String> matchedFiles) {
        if (Files.exists(dir.resolve(filename))) {
            matchedFiles.add(filename);
        }
    }

    /**
     * Check if a build tool wrapper script exists in the given directory.
     */
    private void checkWrapper(Path dir, String wrapperName, List<String> wrapperFiles) {
        if (Files.exists(dir.resolve(wrapperName))) {
            wrapperFiles.add(wrapperName);
        }
    }

    /**
     * Validate a pom.xml file for structural and content issues.
     */
    private List<Map<String, Object>> validatePomXml(Path pomXml) {
        List<Map<String, Object>> issues = new ArrayList<>();

        try {
            String content = Files.readString(pomXml);

            // Check XML well-formedness with basic heuristics
            // Check for unmatched tags
            if (!content.contains("<project") || !content.contains("</project>")) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("severity", "ERROR");
                issue.put("path", "pom.xml");
                issue.put("message", "Missing <project> root element");
                issue.put("suggestion", "Add <project> root element with correct namespace");
                issues.add(issue);
                return issues;
            }

            // Check required elements, accounting for parent POM inheritance
            // Extract the parent block if present
            String contentOutsideParent = content;
            boolean hasParentBlock = content.contains("<parent>") && content.contains("</parent>");
            if (hasParentBlock) {
                int parentStart = content.indexOf("<parent>");
                int parentEnd = content.indexOf("</parent>") + "</parent>".length();
                contentOutsideParent = content.substring(0, parentStart) +
                        content.substring(parentEnd);
            }

            String[] requiredElements = {"modelVersion", "groupId", "artifactId", "version"};
            for (String element : requiredElements) {
                boolean hasOpen = contentOutsideParent.contains("<" + element + ">");
                boolean hasClose = contentOutsideParent.contains("</" + element + ">");

                // groupId and version can be inherited from parent POM
                boolean canBeInherited = element.equals("groupId") || element.equals("version");

                if (!hasOpen && canBeInherited && hasParentBlock) {
                    // Check if it's present in the parent block
                    String parentBlock = content.substring(
                            content.indexOf("<parent>"),
                            content.indexOf("</parent>") + "</parent>".length());
                    if (parentBlock.contains("<" + element + ">")) {
                        // Inherited from parent — valid
                        continue;
                    }
                }

                if (!hasOpen) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "ERROR");
                    issue.put("path", "pom.xml");
                    issue.put("message", "Missing required element: <" + element + ">");
                    issue.put("suggestion", "Add <" + element + "> element inside <project>");
                    issues.add(issue);
                } else if (!hasClose) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "ERROR");
                    issue.put("path", "pom.xml");
                    issue.put("message", "Unclosed element: <" + element + ">");
                    issue.put("suggestion", "Add closing </" + element + "> tag");
                    issues.add(issue);
                }
            }

            // Check for duplicate dependency declarations
            Map<String, Integer> depCounts = new LinkedHashMap<>();
            Pattern depPattern = Pattern.compile("<artifactId>([^<]+)</artifactId>");
            Matcher depMatcher = depPattern.matcher(content);
            while (depMatcher.find()) {
                String artifactId = depMatcher.group(1);
                depCounts.merge(artifactId, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : depCounts.entrySet()) {
                if (entry.getValue() > 1) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "WARNING");
                    issue.put("path", "pom.xml");
                    issue.put("message", "Duplicate dependency declaration: " + entry.getKey() +
                            " (declared " + entry.getValue() + " times)");
                    issue.put("suggestion", "Remove duplicate <dependency> entry for " + entry.getKey());
                    issues.add(issue);
                }
            }

            // Check plugin version consistency (warn if multiple versions of same plugin)
            Map<String, Set<String>> pluginVersions = new LinkedHashMap<>();
            Pattern pluginPattern = Pattern.compile(
                    "<plugin>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*(?:<version>([^<]+)</version>)?",
                    Pattern.DOTALL);
            Matcher pluginMatcher = pluginPattern.matcher(content);
            while (pluginMatcher.find()) {
                String groupId = pluginMatcher.group(1);
                String artifactId = pluginMatcher.group(2);
                String version = pluginMatcher.group(3) != null ? pluginMatcher.group(3) : "UNSPECIFIED";
                String key = groupId + ":" + artifactId;
                pluginVersions.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(version);
            }
            for (Map.Entry<String, Set<String>> entry : pluginVersions.entrySet()) {
                if (entry.getValue().size() > 1) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "WARNING");
                    issue.put("path", "pom.xml");
                    issue.put("message", "Inconsistent plugin versions for " + entry.getKey() +
                            ": " + String.join(", ", entry.getValue()));
                    issue.put("suggestion", "Use <pluginManagement> to centralize plugin version for " +
                            entry.getKey().split(":")[1]);
                    issues.add(issue);
                }
            }

        } catch (IOException e) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("severity", "ERROR");
            issue.put("path", "pom.xml");
            issue.put("message", "Cannot read pom.xml: " + e.getMessage());
            issues.add(issue);
        }

        return issues;
    }

    /**
     * Validate a build.gradle or build.gradle.kts file for basic syntax.
     */
    private List<Map<String, Object>> validateBuildGradle(Path buildFile, boolean isKotlin) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String filename = buildFile.getFileName().toString();

        try {
            String content = Files.readString(buildFile);

            if (content.isBlank()) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("severity", "ERROR");
                issue.put("path", filename);
                issue.put("message", "Build file is empty");
                issue.put("suggestion", "Add at minimum a buildscript block or plugin declarations");
                issues.add(issue);
                return issues;
            }

            // Check brace balance
            int braceDepth = 0;
            int lineNum = 0;
            for (String line : content.split("\\r?\\n")) {
                lineNum++;
                for (char c : line.toCharArray()) {
                    if (c == '{') braceDepth++;
                    if (c == '}') braceDepth--;
                }
            }
            if (braceDepth != 0) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("severity", "ERROR");
                issue.put("path", filename);
                issue.put("message", "Unbalanced braces: " + (braceDepth > 0 ? "missing " + braceDepth + " closing brace(s)" : "extra " + (-braceDepth) + " closing brace(s)"));
                issue.put("suggestion", "Ensure all opening braces '{' have matching closing braces '}'");
                issues.add(issue);
            }

            // Check for unclosed string literals
            int quoteBalance = 0;
            for (char c : content.toCharArray()) {
                if (c == '\'' || c == '"') quoteBalance++;
            }
            if (quoteBalance % 2 != 0) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("severity", "ERROR");
                issue.put("path", filename);
                issue.put("message", "Unclosed string literal detected");
                issue.put("suggestion", "Ensure all string literals are properly closed with matching quotes");
                issues.add(issue);
            }

            // In Groovy build.gradle, check for common issues
            if (!isKotlin) {
                // Check for missing 'apply plugin' or 'plugins' block
                boolean hasPlugins = content.contains("apply plugin")
                        || content.contains("plugins {")
                        || content.contains("plugins{");
                if (!hasPlugins && content.contains("dependencies")) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "WARNING");
                    issue.put("path", filename);
                    issue.put("message", "No plugin declarations found but dependencies are defined");
                    issue.put("suggestion", "Add 'apply plugin: \"java\"' or 'plugins { id \"java\" }'");
                    issues.add(issue);
                }
            }

            if (isKotlin) {
                // Check for common Kotlin DSL syntax issues
                // e.g. using Groovy-style strings in Kotlin DSL
                if (content.contains("'") && content.contains("implementation '")) {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("severity", "WARNING");
                    issue.put("path", filename);
                    issue.put("message", "Using Groovy-style single quotes in Kotlin DSL");
                    issue.put("suggestion", "Use double quotes in Kotlin DSL: implementation(\"group:artifact:version\")");
                    issues.add(issue);
                }
            }

        } catch (IOException e) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("severity", "ERROR");
            issue.put("path", filename);
            issue.put("message", "Cannot read build file: " + e.getMessage());
            issues.add(issue);
        }

        return issues;
    }
}