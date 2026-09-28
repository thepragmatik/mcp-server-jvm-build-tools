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
            "toolCount");
    private static final Set<String> BOOLEANS =
            Set.of("success", "valid", "detected", "authorized", "hasErrors", "hasWarnings");
    private static final List<String> BUILD_TOOLS = List.of("maven", "gradle", "sbt");
    private static final Set<String> STATUSES =
            Set.of("success", "failed", "error", "running", "completed", "cancelled");
    private static final Pattern VERSION = Pattern.compile("\\b[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-[A-Za-z0-9.-]+)?\\b");

    private static final Pattern DIAGNOSTIC_LINE =
            Pattern.compile("(?i).*(?:\\berror\\b|\\bfailed\\b|\\bwarning\\b|\\bexception\\b).*");
    private static final List<Replacement> REDACTIONS = List.of(
            new Replacement("(?i)\\b(?:authorization\\s*:\\s*bearer|bearer)\\s+[^\\s,;]+", "[redacted-secret]"),
            new Replacement(
                    "(?i)\\b(?:password|passwd|token|api[_-]?key|secret|credential|private[_-]?key)\\s*[:=]\\s*[^\\s,;]+",
                    "[redacted-secret]"),
            new Replacement("(?i)\\b(?:https?|file)://[^\\s<>()]+", "[redacted-url]"),
            new Replacement("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[redacted-email]"),
            new Replacement("(?i)(?:[A-Z]:\\\\|/)(?:[^\\s:;,'\"()<>]+[/\\\\])*[^\\s:;,'\"()<>]*", "[redacted-path]"),
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
                    copyVersion(root, "latestVersion", safe);
                    copyVersion(root, "currentVersion", safe);
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
                    List<String> diagnostics = new ArrayList<>();
                    copyDiagnostics(root.get("errors"), diagnostics);
                    copyDiagnostics(root.get("warnings"), diagnostics);
                    if (!diagnostics.isEmpty()) {
                        safe.put("diagnostics", diagnostics);
                    }
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
        if ("execute_build_command".equals(toolName) && output != null) {
            if (output.contains("BUILD SUCCESS")) {
                safe.put("success", true);
            } else if (output.contains("BUILD FAILURE") || output.contains("BUILD FAILED")) {
                safe.put("success", false);
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

    private static void copyCounter(JsonNode source, String key, Map<String, Object> target) {
        JsonNode value = source.get(key);
        if (value != null && value.isIntegralNumber() && value.longValue() >= 0 && value.longValue() <= 1_000_000) {
            target.put(key, value.longValue());
        }
    }

    private static void copyDiagnostics(JsonNode source, List<String> target) {
        if (source == null || !source.isArray()) {
            return;
        }
        for (JsonNode item : source) {
            if (target.size() >= MAX_DIAGNOSTICS) {
                return;
            }
            JsonNode message = item.isObject() ? item.get("message") : item;
            if (message != null && message.isTextual()) {
                target.add(redact(message.asText()));
            }
        }
    }

    private static void copyPlainDiagnostics(String output, Map<String, Object> safe) {
        List<String> diagnostics = output.lines()
                .filter(line -> DIAGNOSTIC_LINE.matcher(line).matches())
                .limit(MAX_DIAGNOSTICS)
                .map(ModelOutputPolicy::redact)
                .toList();
        if (!diagnostics.isEmpty()) {
            safe.put("diagnostics", diagnostics);
        }
    }

    static String redact(String value) {
        String safe = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
        for (Replacement replacement : REDACTIONS) {
            safe = replacement.pattern().matcher(safe).replaceAll(replacement.replacement());
        }
        return safe.length() > MAX_MESSAGE_LENGTH ? safe.substring(0, MAX_MESSAGE_LENGTH) : safe;
    }

    private record Replacement(Pattern pattern, String replacement) {
        private Replacement(String expression, String replacement) {
            this(Pattern.compile(expression), replacement);
        }
    }
}
