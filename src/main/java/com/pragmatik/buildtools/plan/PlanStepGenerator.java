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
package com.pragmatik.buildtools.plan;

import com.pragmatik.buildtools.build.BuildToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates structured build plan steps from natural language descriptions.
 *
 * <p>Analyzes descriptions for build lifecycle keywords and orders them
 * by convention: clean → validate → compile → test → package → install → deploy.
 */
public class PlanStepGenerator {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "clean",
            "compile",
            "test",
            "package",
            "install",
            "deploy",
            "validate",
            "build",
            "check",
            "verify",
            "assemble",
            "integrationTest",
            "testIntegration");

    private static final List<String> PHASE_ORDER = List.of(
            "clean",
            "validate",
            "compile",
            "build",
            "test",
            "check",
            "verify",
            "integrationTest",
            "testIntegration",
            "package",
            "jar",
            "assemble",
            "install",
            "publish",
            "deploy");

    private final BuildToolProvider provider;

    public PlanStepGenerator() {
        this.provider = new BuildToolProvider();
    }

    public PlanStepGenerator(BuildToolProvider provider) {
        this.provider = provider;
    }

    /**
     * Generate steps from a natural language description for a specific build tool.
     *
     * @param description  Natural language description of the build workflow
     * @param buildToolName Target build tool ("maven", "gradle", "sbt")
     * @return Ordered list of plan steps
     * @throws IllegalArgumentException if description is empty or contains invalid commands
     */
    public List<PlanStep> generateSteps(String description, String buildToolName) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be null or empty");
        }

        Set<String> keywords = extractKeywords(description.toLowerCase());
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("No recognized build commands found in description. "
                    + "Expected keywords like: build, compile, test, package, install, "
                    + "deploy, validate, clean, check, jar, publish.");
        }

        // Validate against allowed commands
        for (String kw : keywords) {
            if (!ALLOWED_COMMANDS.contains(kw)) {
                throw new IllegalArgumentException("Unrecognized build command: '" + kw + "'. " + "Supported: "
                        + String.join(", ", ALLOWED_COMMANDS));
            }
        }

        // Order by lifecycle phase
        List<String> ordered = keywords.stream()
                .sorted(Comparator.comparingInt(k -> {
                    int idx = PHASE_ORDER.indexOf(k);
                    return idx >= 0 ? idx : Integer.MAX_VALUE;
                }))
                .collect(Collectors.toCollection(ArrayList::new));

        // Convert to meaningful commands per build tool
        List<PlanStep> steps = new ArrayList<>();
        String prevStepId = null;
        int stepNum = 1;

        for (String keyword : ordered) {
            String stepId = "step-" + stepNum;
            String command = toCommand(keyword, buildToolName);

            String label = toLabel(keyword);
            List<String> dependsOn = prevStepId != null ? List.of(prevStepId) : List.of();
            int timeout = timeoutForKey(keyword);

            steps.add(new PlanStep(stepId, label, command, dependsOn, timeout, "stop", true, 0));
            prevStepId = stepId;
            stepNum++;
        }

        return steps;
    }

    /**
     * Generate steps from a natural language description, auto-detecting the build tool
     * from the project directory.
     *
     * @param description  Natural language description
     * @param projectDir   Project directory for tool auto-detection
     * @param buildToolName Explicit tool name (nullable — auto-detect if null)
     * @return Ordered list of plan steps
     * @throws IllegalArgumentException if description is empty or tool cannot be detected
     */
    public List<PlanStep> generateSteps(String description, String projectDir, String buildToolName)
            throws IOException {
        String toolName = buildToolName;
        if (toolName == null || toolName.isBlank()) {
            // Auto-detect from project directory
            var allTools = provider.getAllTools();
            for (var entry : allTools.entrySet()) {
                if (detectTool(Path.of(projectDir), entry.getKey())) {
                    toolName = entry.getKey();
                    break;
                }
            }
            if (toolName == null) {
                throw new IllegalArgumentException("Cannot auto-detect build tool from project directory: " + projectDir
                        + ". Specify buildToolName explicitly.");
            }
        }
        return generateSteps(description, toolName);
    }

    /**
     * Parse explicit step definitions from a JSON array string.
     *
     * @param stepsJson JSON array of step objects
     * @return Ordered list of plan steps
     * @throws IllegalArgumentException if JSON is invalid
     */
    public List<PlanStep> parseStepsJson(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            throw new IllegalArgumentException("stepsJson cannot be null or empty");
        }

        try {
            return parseStepsArray(stepsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid steps JSON: " + e.getMessage(), e);
        }
    }

    private List<PlanStep> parseStepsArray(String json) {
        List<PlanStep> steps = new ArrayList<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("Steps JSON must be an array");
        }

        // Use a simple parser
        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();

        // Split top-level objects
        int i = 0;
        while (i < trimmed.length()) {
            // Skip whitespace/comma
            while (i < trimmed.length() && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == ',')) i++;
            if (i >= trimmed.length()) break;

            if (trimmed.charAt(i) == '{') {
                int braceCount = 0;
                int start = i;
                while (i < trimmed.length()) {
                    if (trimmed.charAt(i) == '{') braceCount++;
                    if (trimmed.charAt(i) == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            i++;
                            break;
                        }
                    }
                    if (trimmed.charAt(i) == '"' && (i == start || trimmed.charAt(i - 1) != '\\')) {
                        // Skip string content
                    }
                    i++;
                }
                String objJson = trimmed.substring(start, i);
                steps.add(extractStepFromMap(objJson));
            } else {
                i++;
            }
        }

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Steps array is empty");
        }

        return steps;
    }

    private PlanStep extractStepFromMap(String json) {
        // Simple field extraction by string search
        String id = extractJsonString(json, "id");
        String label = extractJsonString(json, "label");
        String command = extractJsonString(json, "command");
        List<String> dependsOn = extractJsonArray(json, "dependsOn");
        int timeout = extractJsonInt(json, "timeoutSeconds", 300);
        String onFailure = extractJsonString(json, "onFailure");
        if (onFailure == null) onFailure = "stop";
        boolean captureOutput = !"false".equals(extractJsonString(json, "captureOutput"));
        int retryCount = extractJsonInt(json, "retryCount", 0);

        if (id == null || label == null || command == null) {
            throw new IllegalArgumentException("Step must have id, label, and command fields");
        }

        return new PlanStep(id, label, command, dependsOn, timeout, onFailure, captureOutput, retryCount);
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                sb.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<String> extractJsonArray(String json, String key) {
        List<String> items = new ArrayList<>();
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start < 0) return items;
        start += search.length();
        int end = json.indexOf(']', start);
        if (end < 0) return items;
        String content = json.substring(start, end);
        // Parse string items
        int i = 0;
        while (i < content.length()) {
            while (i < content.length() && (content.charAt(i) == ' ' || content.charAt(i) == ',')) i++;
            if (i >= content.length()) break;
            if (content.charAt(i) == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < content.length() && content.charAt(i) != '"') {
                    if (content.charAt(i) == '\\') {
                        sb.append(content.charAt(i + 1));
                        i += 2;
                    } else {
                        sb.append(content.charAt(i));
                        i++;
                    }
                }
                i++;
                items.add(sb.toString());
            } else {
                i++;
            }
        }
        return items;
    }

    private int extractJsonInt(String json, String key, int defaultVal) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return defaultVal;
        start += search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else break;
        }
        return sb.isEmpty() ? defaultVal : Integer.parseInt(sb.toString());
    }

    // ─── Private helpers ────────────────────────────────────────────

    private Set<String> extractKeywords(String description) {
        Set<String> keywords = new LinkedHashSet<>();
        String[] words = description.toLowerCase().split("[\\s,;.]+");
        for (String word : words) {
            if (ALLOWED_COMMANDS.contains(word)) {
                keywords.add(word);
            }
        }
        return keywords;
    }

    private String toCommand(String keyword, String buildToolName) {
        if ("maven".equals(buildToolName) || buildToolName == null) {
            return switch (keyword) {
                case "jar" -> "package";
                case "build" -> "compile";
                case "publish" -> "deploy";
                case "check" -> "verify";
                case "integrationTest", "testIntegration" -> "verify";
                default -> keyword;
            };
        } else if ("gradle".equals(buildToolName)) {
            return switch (keyword) {
                case "compile" -> "compileJava";
                case "validate" -> "check";
                case "jar" -> "jar";
                case "publish" -> "publish";
                case "install" -> "publishToMavenLocal";
                case "integrationTest", "testIntegration" -> "integrationTest";
                default -> keyword;
            };
        } else if ("sbt".equals(buildToolName)) {
            return switch (keyword) {
                case "compile" -> "compile";
                case "test" -> "test";
                case "package" -> "package";
                case "clean" -> "clean";
                case "validate" -> "compile";
                default -> keyword;
            };
        }
        return keyword;
    }

    private String toLabel(String keyword) {
        return switch (keyword) {
            case "compile" -> "Compile source code";
            case "build" -> "Build project";
            case "test" -> "Run unit tests";
            case "package" -> "Package artifact";
            case "install" -> "Install to local repository";
            case "deploy" -> "Deploy artifact";
            case "validate" -> "Validate project";
            case "clean" -> "Clean build artifacts";
            case "check" -> "Run checks";
            case "verify" -> "Run verification";
            case "jar" -> "Create JAR";
            case "publish" -> "Publish artifact";
            case "integrationTest", "testIntegration" -> "Run integration tests";
            case "assemble" -> "Assemble outputs";
            default -> "Execute " + keyword;
        };
    }

    private int timeoutForKey(String keyword) {
        return switch (keyword) {
            case "clean", "validate" -> 60;
            case "compile", "build", "jar" -> 120;
            case "test", "check", "verify" -> 300;
            case "package", "assemble" -> 120;
            case "install", "deploy", "publish" -> 180;
            case "integrationTest", "testIntegration" -> 600;
            default -> 300;
        };
    }

    private boolean detectTool(Path dir, String toolName) {
        return switch (toolName) {
            case "maven" -> Files.exists(dir.resolve("pom.xml"));
            case "gradle" ->
                Files.exists(dir.resolve("build.gradle"))
                        || Files.exists(dir.resolve("build.gradle.kts"))
                        || Files.exists(dir.resolve("settings.gradle"))
                        || Files.exists(dir.resolve("settings.gradle.kts"));
            case "sbt" -> Files.exists(dir.resolve("build.sbt"));
            default -> false;
        };
    }
}
