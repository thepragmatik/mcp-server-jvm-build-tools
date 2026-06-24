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
package com.pragmatik.buildtools.build;

import com.pragmatik.buildtools.tool.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP resource service that exposes project build artifacts, dependency trees,
 * and configuration files as navigable MCP resources.
 * <p>
 * Resources follow the MCP resource URI scheme:
 * <ul>
 *   <li>{@code build://{project}/output} — recent build output</li>
 *   <li>{@code build://{project}/dependencies} — dependency tree</li>
 *   <li>{@code build://{project}/config} — build configuration files</li>
 *   <li>{@code build://{project}/test-results} — parsed test results</li>
 * </ul>
 */
@Service
public class BuildResourceService {

    private final BuildToolProvider provider;

    public BuildResourceService(BuildToolProvider provider) {
        this.provider = provider;
    }

    /**
     * List all available build resources for a project.
     */
    @Tool(
            name = "list_build_resources",
            description = "List all available build resources for a project directory. "
                    + "Returns a JSON array of resource URIs with descriptions and content types. "
                    + "Resources include build outputs, dependency information, configuration files, "
                    + "and test results.")
    public String listBuildResources(
            @ToolParam(required = true, description = "Project directory path") String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        List<Map<String, Object>> resources = new ArrayList<>();
        String projectName = dir.getFileName().toString();

        // Resource 1: Build configuration
        Map<String, Object> configRes = resourceEntry(
                "build://" + projectName + "/config",
                "Build Configuration",
                "The build configuration files (pom.xml, build.gradle, etc.) for this project",
                "text/plain");
        resources.add(configRes);

        // Resource 2: Dependency information
        Map<String, Object> depRes = resourceEntry(
                "build://" + projectName + "/dependencies",
                "Dependency Information",
                "Dependency tree and version information for this project",
                "application/json");
        resources.add(depRes);

        // Resource 3: Build output
        Map<String, Object> outputRes = resourceEntry(
                "build://" + projectName + "/output",
                "Recent Build Output",
                "The most recent build output with parsed test results, errors, and warnings",
                "application/json");
        resources.add(outputRes);

        // Resource 4: Test results
        Map<String, Object> testRes = resourceEntry(
                "build://" + projectName + "/test-results",
                "Test Results",
                "Structured test results from the most recent test run",
                "application/json");
        resources.add(testRes);

        // Resource 5: Build tool info
        BuildTool detected = provider.resolve(null, dir);
        Map<String, Object> toolRes = resourceEntry(
                "build://" + projectName + "/tool-info",
                "Build Tool Information",
                "Information about the detected build tool: " + detected.getName() + " v" + detected.version(),
                "application/json");
        resources.add(toolRes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", projectName);
        result.put("projectDir", dir.toString());
        result.put("detectedTool", detected.getName());
        result.put("resourceCount", resources.size());
        result.put("resources", resources);

        return JsonUtils.toJson(result);
    }

    /**
     * Read a specific build resource by URI.
     */
    @Tool(
            name = "read_build_resource",
            description = "Read the contents of a build resource by its URI. "
                    + "Use list_build_resources first to discover available resource URIs. "
                    + "Supports: build config files, dependency info, build outputs, and test results.")
    public String readBuildResource(
            @ToolParam(
                            required = true,
                            description = "Resource URI from list_build_resources (e.g., 'build://myproject/config')")
                    String resourceUri,
            @ToolParam(required = true, description = "Project directory path") String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }

        String projectName = dir.getFileName().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceUri", resourceUri);
        result.put("project", projectName);

        if (resourceUri.endsWith("/config")) {
            return readBuildConfig(dir, result);
        } else if (resourceUri.endsWith("/dependencies")) {
            return readDependencyInfo(dir, result);
        } else if (resourceUri.endsWith("/tool-info")) {
            return readToolInfo(dir, result);
        } else if (resourceUri.endsWith("/output") || resourceUri.endsWith("/test-results")) {
            return readBuildOutputNote(dir, result);
        } else {
            return JsonUtils.errorJson("Unknown resource URI: " + resourceUri
                    + ". Use list_build_resources to discover available resources.");
        }
    }

    // ─── Resource readers ──────────────────────────────────────────────────

    private String readBuildConfig(Path dir, Map<String, Object> result) {
        List<Map<String, Object>> files = new ArrayList<>();

        for (String name : new String[] {
            "pom.xml", "build.gradle", "build.gradle.kts", "build.sbt", "settings.gradle", "settings.gradle.kts"
        }) {
            Path file = dir.resolve(name);
            if (Files.exists(file)) {
                try {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", name);
                    entry.put("size", Files.size(file));
                    entry.put("content", Files.readString(file));
                    entry.put("readable", true);
                    files.add(entry);
                } catch (IOException e) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", name);
                    entry.put("exists", true);
                    entry.put("readable", false);
                    entry.put("error", e.getMessage());
                    files.add(entry);
                }
            }
        }

        result.put("contentType", "text/plain");
        result.put("fileCount", files.size());
        result.put("files", files);
        result.put("available", !files.isEmpty());

        return JsonUtils.toJson(result);
    }

    private String readDependencyInfo(Path dir, Map<String, Object> result) {
        // Read the build file to extract dependency declarations
        List<Map<String, String>> dependencies = new ArrayList<>();

        // Try pom.xml
        Path pomXml = dir.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            try {
                String content = Files.readString(pomXml);
                // Extract dependency coordinates
                java.util.regex.Pattern depPattern = java.util.regex.Pattern.compile(
                        "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*(?:<version>([^<]*)</version>)?",
                        java.util.regex.Pattern.DOTALL);
                java.util.regex.Matcher m = depPattern.matcher(content);
                while (m.find()) {
                    Map<String, String> dep = new LinkedHashMap<>();
                    dep.put("groupId", m.group(1).trim());
                    dep.put("artifactId", m.group(2).trim());
                    dep.put("version", m.group(3) != null ? m.group(3).trim() : "[managed]");
                    dep.put("buildTool", "maven");
                    dependencies.add(dep);
                }
            } catch (IOException ignored) {
            }
        }

        result.put("contentType", "application/json");
        result.put("dependencyCount", dependencies.size());
        result.put("dependencies", dependencies);
        result.put("available", !dependencies.isEmpty());

        if (dependencies.isEmpty()) {
            result.put(
                    "note",
                    "Dependency extraction is currently supported for Maven (pom.xml). "
                            + "Gradle and SBT dependency extraction coming soon.");
        }

        return JsonUtils.toJson(result);
    }

    private String readToolInfo(Path dir, Map<String, Object> result) {
        BuildTool tool = provider.resolve(null, dir);
        Map<String, Object> toolInfo = new LinkedHashMap<>();
        toolInfo.put("name", tool.getName());
        toolInfo.put("version", tool.version());
        toolInfo.put("supportedCommands", tool.getSupportedCommands());

        result.put("contentType", "application/json");
        result.put("tool", toolInfo);
        return JsonUtils.toJson(result);
    }

    private String readBuildOutputNote(Path dir, Map<String, Object> result) {
        result.put("contentType", "application/json");
        result.put("available", false);
        result.put(
                "note",
                "Build output is available on-demand. Use analyze_build_output or "
                        + "execute_build_command with the desired build command to get real-time results. "
                        + "Resource URIs are provided for MCP resource discovery compatibility.");
        return JsonUtils.toJson(result);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static Map<String, Object> resourceEntry(String uri, String name, String description, String mimeType) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("uri", uri);
        entry.put("name", name);
        entry.put("description", description);
        entry.put("mimeType", mimeType);
        return entry;
    }
}
