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
package com.pragmatik.buildtools.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controls what local tool results may send to an MCP client. This is an egress boundary:
 * only selected aggregate fields and bounded, redacted diagnostics are emitted.
 */
@Component
public final class ModelOutputPolicy {
    private static final int MAX_DIAGNOSTICS = 12;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_RESULT_CHARS = 256_000;
    private static final Set<String> COUNTERS = Set.of(
            "total",
            "passed",
            "failed",
            "errors",
            "skipped",
            "errorCount",
            "warningCount",
            "testCount",
            "failureCount",
            "dependencyCount",
            "resourceCount",
            "templateCount",
            "paramCount",
            "moduleCount",
            "frameworkCount",
            "phaseCount",
            "totalTrackedBuilds",
            "suggestionCount",
            "conflictCount",
            "filesAnalyzed",
            "issueCount",
            "versionCount",
            "totalVersions",
            "toolCount");
    private static final Set<String> BOOLEANS = Set.of(
            "success",
            "valid",
            "detected",
            "authorized",
            "hasErrors",
            "hasWarnings",
            "allParamsResolved",
            "multiModule",
            "hasRootProject",
            "hasExplicitTestConfig",
            "upgradeAvailable");
    private static final List<String> BUILD_TOOLS = List.of("maven", "gradle", "sbt");
    private static final Set<String> PROMPTS =
            Set.of("prompt_build_and_test", "prompt_dependency_audit", "prompt_build_diagnosis");
    private static final Set<String> STATUSES =
            Set.of("success", "failed", "error", "running", "completed", "cancelled");
    private static final Pattern VERSION = Pattern.compile("\\b[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-[A-Za-z0-9.-]+)?\\b");

    private static final Pattern DIAGNOSTIC_LINE =
            Pattern.compile("(?i).*(?:\\berror\\b|\\bfailed\\b|\\bwarning\\b|\\bexception\\b).*");
    private static final Pattern FILE_LOCATION = Pattern.compile(
            "(?i)(?:^|[\\s(])(?:[^\\s:()]+[/\\\\])?[^\\s:()]+\\.(java|kt|scala|xml|gradle|kts|sbt):(?:\\[)?([1-9][0-9]{0,6})");
    private static final List<Replacement> REDACTIONS = List.of(
            new Replacement("(?i)\\b(?:authorization\\s*:\\s*bearer|bearer)\\s+[^\\s,;]+", "[redacted-secret]"),
            new Replacement(
                    "(?i)\\b(?:password|passwd|token|api[_-]?key|secret|credential|private[_-]?key)\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\r\\n]+)",
                    "[redacted-secret]"),
            new Replacement("(?i)\\b(?:https?|file)://[^\\s<>()]+", "[redacted-url]"),
            new Replacement("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[redacted-email]"),
            new Replacement("(?i)\"(?:[A-Z]:\\\\|/)[^\"\\r\\n]+\"", "[redacted-path]"),
            new Replacement("(?i)'(?:[A-Z]:\\\\|/)[^'\\r\\n]+'", "[redacted-path]"),
            new Replacement(
                    "(?i)(?:[A-Z]:\\\\|/)[^\\r\\n:;,'\"()<>]*?\\.(?:java|xml|gradle|kts|kt|scala|sbt|properties|txt|log|class|jar)",
                    "[redacted-path]"),
            new Replacement(
                    "(?i)(?<![A-Za-z0-9])(?:[A-Z]:\\\\|/)[^\\r\\n]*?(?=\\s+(?:user|email|token|password|phone|secret)\\s*=|\\r?\\n|$)",
                    "[redacted-path]"),
            new Replacement("(?<!\\d)\\+?\\d[\\d .()-]{8,}\\d(?!\\d)", "[redacted-phone]"),
            new Replacement(
                    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b",
                    "[redacted-id]"),
            new Replacement("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b", "[redacted-address]"),
            new Replacement("\\b(?:[A-Za-z0-9+/_-]{32,}={0,2}|[0-9a-fA-F]{32,})\\b", "[redacted-value]"));

    private final JsonMapper mapper = new JsonMapper();

    public String protect(String toolName, String output) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("completed", true);
        if (output != null && output.length() > MAX_RESULT_CHARS) {
            // Avoid parsing or applying regexes to an unbounded subprocess result.
            // The tail is most likely to contain the final compiler failure.
            output = output.substring(output.length() - MAX_RESULT_CHARS);
            safe.put("truncated", true);
        }
        if (output != null) {
            try {
                JsonNode root = mapper.readTree(output);
                if (root != null && root.isObject()) {
                    JsonNode success = root.get("success");
                    JsonNode error = root.get("error");
                    if ((success != null && success.isBoolean() && !success.booleanValue())
                            || (error != null && !error.isNull() && (!error.isBoolean() || error.booleanValue()))) {
                        safe.put("isError", true);
                    }
                    for (String key : COUNTERS) {
                        copyCounter(root, key, safe);
                    }
                    for (String key : BOOLEANS) {
                        JsonNode value = root.get(key);
                        if (value != null && value.isBoolean()) {
                            safe.put(key, value.booleanValue());
                        }
                    }
                    copySafeField(root, "status", STATUSES, safe);
                    if ("failed".equals(safe.get("status")) || "error".equals(safe.get("status"))) {
                        safe.put("isError", true);
                    }
                    copySafeField(root, "detectedTool", Set.of("maven", "gradle", "sbt", "mixed", "unknown"), safe);
                    copyVersion(root, "latestVersion", safe);
                    copyVersion(root, "currentVersion", safe);
                    copyVersion(root, "latestStable", safe);
                    copyVersion(root, "scalaVersion", safe);
                    copyVersion(root, "sbtVersion", safe);
                    if ("analyze_pom_dependencies".equals(toolName)) {
                        copyArrayCount(root, "dependencies", "dependencyCount", safe);
                        copyArrayCount(root, "managedDependencies", "managedDependencyCount", safe);
                        copyArrayCount(root, "importedBoms", "importedBomCount", safe);
                    }
                    if ("scan_dependency_cves".equals(toolName)) {
                        copyCounts(
                                root.get("scanSummary"),
                                Set.of("totalDeps", "vulnerableDeps", "criticalCount", "highCount"),
                                safe);
                    }
                    if ("profile_build".equals(toolName)) {
                        copyDuration(root, "durationSeconds", safe);
                        copyDuration(root, "toolReportedSeconds", safe);
                        copyDuration(root, "overheadSeconds", safe);
                    }
                    if ("analyze_build_performance".equals(toolName)) {
                        JsonNode potential = root.get("optimizationPotential");
                        if (potential != null && potential.isObject()) {
                            copySafeField(potential, "level", Set.of("LOW", "MEDIUM", "HIGH"), safe);
                        }
                    }
                    if ("check_java_compatibility".equals(toolName)) {
                        JsonNode verdict = root.get("verdict");
                        if (verdict != null && verdict.isObject()) {
                            JsonNode compatible = verdict.get("compatible");
                            if (compatible != null && compatible.isBoolean()) {
                                safe.put("compatible", compatible.booleanValue());
                            }
                        }
                    }
                    if ("list_dependency_resources".equals(toolName)) {
                        copyAvailableBuildTools(root.get("resources"), safe);
                    }
                    if ("list_build_resources".equals(toolName)) {
                        copyResourceKinds(root.get("resources"), safe);
                    }
                    if (PROMPTS.contains(toolName)) {
                        copyPrompt(toolName, root, safe);
                    }
                    if ("detect_build_tool".equals(toolName)) {
                        JsonNode detected = root.get("detectedTools");
                        if (detected != null && detected.isArray()) {
                            List<String> names = new ArrayList<>();
                            for (JsonNode item : detected) {
                                if (item.isTextual() && BUILD_TOOLS.contains(item.asText())) {
                                    names.add(item.asText());
                                }
                            }
                            safe.put("detectedTools", names);
                        }
                    }
                    JsonNode tests = root.get("testSummary");
                    if (tests != null && tests.isObject()) {
                        Map<String, Object> counts = new LinkedHashMap<>();
                        for (String key : COUNTERS) {
                            copyCounter(tests, key, counts);
                        }
                        if (!counts.isEmpty()) {
                            safe.put("testSummary", counts);
                        }
                    }
                    List<Map<String, Object>> diagnostics = new ArrayList<>();
                    boolean diagnosticsTruncated = copyDiagnostics(root.get("errors"), "error", diagnostics);
                    diagnosticsTruncated |= copyDiagnostics(root.get("warnings"), "warning", diagnostics);
                    putDiagnostics(safe, diagnostics, diagnosticsTruncated);
                } else {
                    copyPlainDiagnostics(output, safe);
                }
            } catch (tools.jackson.core.JacksonException ignored) {
                copyPlainDiagnostics(output, safe);
            }
        }
        if ("get_build_tool_version".equals(toolName) && output != null) {
            var matcher = VERSION.matcher(output);
            if (matcher.find()) {
                safe.put("version", matcher.group());
            }
        }
        if ("list_build_tools".equals(toolName) && output != null) {
            List<String> names = new ArrayList<>();
            for (String name : BUILD_TOOLS) {
                if (output.lines().anyMatch(line -> line.startsWith(name + ":"))) {
                    names.add(name);
                }
            }
            safe.put("tools", names);
        }
        if ("list_available_scopes".equals(toolName)) {
            safe.put("scopes", ToolPermission.allScopes());
        }
        if ("execute_build_command".equals(toolName) && output != null) {
            if (output.contains("BUILD SUCCESS")) {
                safe.put("success", true);
            } else if (output.contains("BUILD FAILURE") || output.contains("BUILD FAILED")) {
                safe.put("success", false);
                safe.put("isError", true);
            }
        }
        if (safe.size() == 1) {
            safe.put("details", "No model-visible details");
        }
        return mapper.writeValueAsString(safe);
    }

    private static void copySafeField(JsonNode source, String key, Set<String> allowed, Map<String, Object> target) {
        JsonNode value = source.get(key);
        if (value != null && value.isTextual() && allowed.contains(value.asText())) {
            target.put(key, value.asText());
        }
    }

    private static void copyVersion(JsonNode source, String key, Map<String, Object> target) {
        JsonNode value = source.get(key);
        if (value != null
                && value.isTextual()
                && VERSION.matcher(value.asText()).matches()) {
            target.put(key, value.asText());
        }
    }

    private static void copyPrompt(String toolName, JsonNode source, Map<String, Object> target) {
        JsonNode template = source.get("template");
        if (template == null || !template.isTextual()) {
            return;
        }
        String marker =
                "prompt_build_diagnosis".equals(toolName) ? "Follow this diagnostic workflow:" : "Follow these steps:";
        String text = template.asText();
        int start = text.lastIndexOf(marker);
        if (start >= 0) {
            // The tool prepends user-supplied project paths and commands. Only
            // the final, server-authored workflow section is model-visible.
            target.put("template", redact(text.substring(start), 3_500));
            target.put("promptName", toolName);
        }
    }

    private static void copyAvailableBuildTools(JsonNode resources, Map<String, Object> target) {
        if (resources == null || !resources.isArray()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode resource : resources) {
            JsonNode tool = resource.get("buildTool");
            if (tool != null
                    && tool.isTextual()
                    && BUILD_TOOLS.contains(tool.asText())
                    && !names.contains(tool.asText())) {
                names.add(tool.asText());
            }
        }
        target.put("availableBuildTools", names);
    }

    private static void copyResourceKinds(JsonNode resources, Map<String, Object> target) {
        if (resources == null || !resources.isArray()) {
            return;
        }
        List<String> kinds = new ArrayList<>();
        Set<String> allowed = Set.of("config", "dependencies", "output", "test-results", "tool-info");
        for (JsonNode resource : resources) {
            JsonNode uri = resource.get("uri");
            if (uri != null && uri.isTextual()) {
                String value = uri.asText();
                String kind = value.substring(value.lastIndexOf('/') + 1);
                if (allowed.contains(kind) && !kinds.contains(kind)) {
                    kinds.add(kind);
                }
            }
        }
        target.put("resourceKinds", kinds);
    }

    private static void copyArrayCount(JsonNode source, String key, String resultKey, Map<String, Object> target) {
        JsonNode value = source.get(key);
        if (value != null && value.isArray()) {
            target.put(resultKey, value.size());
        }
    }

    private static void copyCounts(JsonNode source, Set<String> keys, Map<String, Object> target) {
        if (source == null || !source.isObject()) {
            return;
        }
        for (String key : keys) {
            copyCounter(source, key, target);
        }
    }

    private static void copyDuration(JsonNode source, String key, Map<String, Object> target) {
        JsonNode value = source.get(key);
        if (value != null && value.isNumber()) {
            double seconds = value.doubleValue();
            if (Double.isFinite(seconds) && seconds >= 0 && seconds <= 86_400) {
                target.put(key, seconds);
            }
        }
    }

    private static void copyCounter(JsonNode source, String key, Map<String, Object> target) {
        JsonNode value = source.get(key);
        if (value != null && value.isIntegralNumber() && value.longValue() >= 0 && value.longValue() <= 1_000_000) {
            target.put(key, value.longValue());
        }
    }

    private static boolean copyDiagnostics(JsonNode source, String severity, List<Map<String, Object>> target) {
        if (source == null || !source.isArray()) {
            return false;
        }
        boolean truncated = false;
        for (JsonNode item : source) {
            JsonNode message = item.isObject() ? item.get("message") : item;
            if (message != null && message.isTextual()) {
                Map<String, Object> diagnostic = diagnostic(severity, message.asText(), item);
                if (!target.contains(diagnostic)) {
                    if (target.size() == MAX_DIAGNOSTICS) {
                        truncated = true;
                        break;
                    }
                    target.add(diagnostic);
                }
            }
        }
        return truncated;
    }

    private static void copyPlainDiagnostics(String output, Map<String, Object> safe) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean truncated = false;
        for (String line : output.split("\\R")) {
            if (!DIAGNOSTIC_LINE.matcher(line).matches()) {
                continue;
            }
            String severity = line.toLowerCase(java.util.Locale.ROOT).contains("warn") ? "warning" : "error";
            Map<String, Object> diagnostic = diagnostic(severity, line, null);
            if (!diagnostics.contains(diagnostic)) {
                if (diagnostics.size() == MAX_DIAGNOSTICS) {
                    truncated = true;
                    break;
                }
                diagnostics.add(diagnostic);
            }
        }
        putDiagnostics(safe, diagnostics, truncated);
    }

    private static void putDiagnostics(
            Map<String, Object> safe, List<Map<String, Object>> diagnostics, boolean truncated) {
        if (!diagnostics.isEmpty()) {
            safe.put("diagnostics", diagnostics);
        }
        if (truncated) {
            safe.put("diagnosticsTruncated", true);
        }
    }

    private static Map<String, Object> diagnostic(String severity, String text, JsonNode item) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String category;
        String message;
        if (containsAny(lower, "cannot find symbol", "compilation failure", "compilejava", "compilescala",
                "unresolved reference", "not found: value", "type mismatch", "compilation failed", "compiling ")) {
            category = "compilation";
            message = "Compilation failed; inspect the indicated source line locally.";
        } else if (containsAny(lower, "test failed", "tests failed", "there are test failures", "assertionerror",
                "assertion failed", "execution failed for task ':test", "() in ")) {
            category = "test";
            message = "A test failed; inspect the local test report.";
        } else if (containsAny(lower, "could not resolve", "failed to resolve", "could not find artifact",
                "dependency resolution", "non-resolvable", "unresolved dependency")) {
            category = "dependency";
            message = "Dependency resolution failed; inspect local dependency settings.";
        } else if (containsAny(lower, "non-parseable pom", "malformed pom", "build.gradle", "build.sbt",
                "unknown lifecycle phase", "task not found", "plugin configuration", "configuration failed")) {
            category = "configuration";
            message = "Build configuration failed; inspect the local build file.";
        } else if (containsAny(lower, "execution failed", "build failed", "build failure", "exception",
                "timeout", "timed out", "failed")) {
            category = "execution";
            message = "Build execution failed; inspect the local build output.";
        } else {
            category = "other";
            message = "Build reported a diagnostic; inspect the local output.";
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("severity", severity);
        safe.put("category", category);
        String fileType = fileType(item, text);
        if (fileType != null) {
            safe.put("fileType", fileType);
        }
        Integer line = line(item, text);
        if (line != null) {
            safe.put("line", line);
        }
        safe.put("message", message);
        return safe;
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String fileType(JsonNode item, String text) {
        if (item != null && item.isObject()) {
            JsonNode file = item.get("file");
            if (file != null && file.isTextual()) {
                String name = file.asText().toLowerCase(java.util.Locale.ROOT);
                for (String extension : List.of("java", "kt", "scala", "xml", "gradle", "kts", "sbt")) {
                    if (name.endsWith("." + extension)) {
                        return extension;
                    }
                }
            }
        }
        var match = FILE_LOCATION.matcher(text);
        return match.find() ? match.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static Integer line(JsonNode item, String text) {
        if (item != null && item.isObject()) {
            JsonNode value = item.get("line");
            if (value != null && value.isIntegralNumber() && value.intValue() > 0 && value.intValue() <= 9_999_999) {
                return value.intValue();
            }
        }
        var match = FILE_LOCATION.matcher(text);
        return match.find() ? Integer.valueOf(match.group(2)) : null;
    }

    static String redact(String value) {
        return redact(value, MAX_MESSAGE_LENGTH);
    }

    private static String redact(String value, int maxLength) {
        String bounded = value.length() > maxLength * 4 ? value.substring(0, maxLength * 4) : value;
        String safe = bounded.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
        for (Replacement replacement : REDACTIONS) {
            safe = replacement.pattern().matcher(safe).replaceAll(replacement.replacement());
        }
        return safe.length() > maxLength ? safe.substring(0, maxLength) : safe;
    }

    private record Replacement(Pattern pattern, String replacement) {
        private Replacement(String expression, String replacement) {
            this(Pattern.compile(expression), replacement);
        }
    }
}
