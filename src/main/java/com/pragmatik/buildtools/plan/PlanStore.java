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

import com.pragmatik.buildtools.tool.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persistent storage for build plans and their results.
 *
 * <p>Plans are stored in {@code .buildtools/plans/{planId}/plan.json} and
 * results in {@code .buildtools/plans/{planId}/results/results.json}.
 * An in-memory cache provides fast access during the session.
 */
public class PlanStore {

    private final Path basePath;
    private final ConcurrentHashMap<String, BuildPlan> planCache = new ConcurrentHashMap<>();

    public PlanStore(String baseDir) {
        this.basePath = Path.of(baseDir);
    }

    /**
     * Save a plan to disk and cache it in memory.
     */
    public void savePlan(BuildPlan plan) throws IOException {
        Files.createDirectories(basePath.resolve(plan.planId()));
        Path planFile = basePath.resolve(plan.planId()).resolve("plan.json");
        Files.writeString(planFile, serializePlan(plan));
        planCache.put(plan.planId(), plan);
    }

    /**
     * Load a plan from cache or disk.
     */
    public BuildPlan loadPlan(String planId) throws IOException {
        BuildPlan cached = planCache.get(planId);
        if (cached != null) {
            return cached;
        }
        Path planFile = basePath.resolve(planId).resolve("plan.json");
        if (!Files.exists(planFile)) {
            return null;
        }
        String content = Files.readString(planFile);
        BuildPlan plan = deserializePlan(content);
        planCache.put(planId, plan);
        return plan;
    }

    /**
     * Save execution results for a plan.
     */
    public void saveResult(String planId, PlanResult result) throws IOException {
        Path resultDir = basePath.resolve(planId).resolve("results");
        Files.createDirectories(resultDir);
        Path resultFile = resultDir.resolve("result.json");
        Files.writeString(resultFile, serializeResult(result));
    }

    /**
     * Load execution results for a plan.
     */
    public PlanResult loadResult(String planId) throws IOException {
        Path resultFile = basePath.resolve(planId).resolve("results").resolve("result.json");
        if (!Files.exists(resultFile)) {
            return null;
        }
        String content = Files.readString(resultFile);
        return deserializeResult(content);
    }

    /**
     * Check if a plan exists in storage.
     */
    public boolean hasPlan(String planId) {
        if (planCache.containsKey(planId)) {
            return true;
        }
        return Files.exists(basePath.resolve(planId).resolve("plan.json"));
    }

    /**
     * Generate a new unique plan ID.
     */
    public static String generatePlanId() {
        return UUID.randomUUID().toString();
    }

    // ─── Serialization helpers ──────────────────────────────────────

    private String serializePlan(BuildPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", plan.planId());
        map.put("description", plan.description());
        map.put("projectDir", plan.projectDir());
        if (plan.buildToolName() != null) map.put("buildToolName", plan.buildToolName());
        if (plan.buildToolHome() != null) map.put("buildToolHome", plan.buildToolHome());
        map.put("steps", plan.steps().stream().map(this::serializeStep).collect(Collectors.toList()));
        map.put("errorHandling", plan.errorHandling());
        map.put("createdAt", plan.createdAt().toString());
        map.put("ttlSeconds", plan.ttlSeconds());
        return JsonUtils.toJson(map);
    }

    private Map<String, Object> serializeStep(PlanStep step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", step.id());
        m.put("label", step.label());
        m.put("command", step.command());
        m.put("dependsOn", step.dependsOn());
        m.put("timeoutSeconds", step.timeoutSeconds());
        m.put("onFailure", step.onFailure());
        m.put("captureOutput", step.captureOutput());
        m.put("retryCount", step.retryCount());
        return m;
    }

    private BuildPlan deserializePlan(String json) {
        // Simple JSON parsing — extract fields
        Map<String, Object> map = parseSimpleJson(json);
        String planId = (String) map.get("planId");
        String description = (String) map.get("description");
        String projectDir = (String) map.get("projectDir");
        String buildToolName = (String) map.get("buildToolName");
        String buildToolHome = (String) map.get("buildToolHome");
        String errorHandling = (String) map.getOrDefault("errorHandling", "stop");
        String createdAt = (String) map.get("createdAt");
        Number ttl = (Number) map.getOrDefault("ttlSeconds", 3600);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepsRaw = (List<Map<String, Object>>) map.get("steps");
        List<PlanStep> steps = stepsRaw != null
                ? stepsRaw.stream().map(this::deserializeStep).collect(Collectors.toList())
                : List.of();

        return new BuildPlan(
                planId,
                description,
                projectDir,
                buildToolName,
                buildToolHome,
                steps,
                errorHandling,
                java.time.Instant.parse(createdAt),
                ttl.intValue());
    }

    @SuppressWarnings("unchecked")
    private PlanStep deserializeStep(Map<String, Object> m) {
        return new PlanStep(
                (String) m.get("id"),
                (String) m.get("label"),
                (String) m.get("command"),
                (List<String>) m.getOrDefault("dependsOn", List.of()),
                ((Number) m.getOrDefault("timeoutSeconds", 300)).intValue(),
                (String) m.getOrDefault("onFailure", "stop"),
                Boolean.TRUE.equals(m.getOrDefault("captureOutput", true)),
                ((Number) m.getOrDefault("retryCount", 0)).intValue());
    }

    private String serializeResult(PlanResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", result.planId());
        map.put("description", result.description());
        map.put("status", result.status());
        map.put("projectDir", result.projectDir());
        map.put("tool", result.tool());
        map.put("totalDurationSeconds", result.totalDurationSeconds());
        map.put("totalDurationFormatted", result.totalDurationFormatted());
        map.put("steps", result.steps().stream().map(this::serializeStepResult).collect(Collectors.toList()));
        map.put("summary", serializeSummary(result.summary()));
        map.put(
                "errors",
                result.errors().stream().map(this::serializeBuildError).collect(Collectors.toList()));
        map.put("finishedAt", result.finishedAt() != null ? result.finishedAt().toString() : null);
        return JsonUtils.toJson(map);
    }

    private Map<String, Object> serializeStepResult(StepResult sr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sr.id());
        m.put("label", sr.label());
        m.put("status", sr.status());
        m.put("durationSeconds", sr.durationSeconds());
        m.put("success", sr.success());
        m.put("output", sr.output());
        if (sr.testSummary() != null) {
            m.put("testSummary", serializeTestSummary(sr.testSummary()));
        }
        m.put("errors", sr.errors().stream().map(this::serializeBuildError).collect(Collectors.toList()));
        m.put("retryAttempted", sr.retryAttempted());
        m.put("startedAt", sr.startedAt() != null ? sr.startedAt().toString() : null);
        m.put("finishedAt", sr.finishedAt() != null ? sr.finishedAt().toString() : null);
        return m;
    }

    private Map<String, Object> serializeTestSummary(StepResult.TestSummary ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", ts.total());
        m.put("passed", ts.passed());
        m.put("failed", ts.failed());
        m.put("errors", ts.errors());
        m.put("skipped", ts.skipped());
        return m;
    }

    private Map<String, Object> serializeBuildError(StepResult.BuildError be) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", be.file());
        m.put("line", be.line());
        m.put("severity", be.severity());
        m.put("message", be.message());
        return m;
    }

    private Map<String, Object> serializeSummary(PlanSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", s.total());
        m.put("completed", s.completed());
        m.put("failed", s.failed());
        m.put("skipped", s.skipped());
        m.put("errorCount", s.errorCount());
        m.put("warningCount", s.warningCount());
        return m;
    }

    private PlanResult deserializeResult(String json) {
        Map<String, Object> map = parseSimpleJson(json);
        String planId = (String) map.get("planId");
        String description = (String) map.get("description");
        String status = (String) map.get("status");
        String projectDir = (String) map.get("projectDir");
        String tool = (String) map.get("tool");
        double totalDur = ((Number) map.getOrDefault("totalDurationSeconds", 0.0)).doubleValue();
        String totalDurFmt = (String) map.getOrDefault("totalDurationFormatted", "");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepsRaw = (List<Map<String, Object>>) map.get("steps");
        List<StepResult> steps = stepsRaw != null
                ? stepsRaw.stream().map(this::deserializeStepResult).collect(Collectors.toList())
                : List.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> summaryRaw = (Map<String, Object>) map.get("summary");
        PlanSummary summary = summaryRaw != null ? deserializeSummary(summaryRaw) : null;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errorsRaw = (List<Map<String, Object>>) map.get("errors");
        List<StepResult.BuildError> errors = errorsRaw != null
                ? errorsRaw.stream().map(this::deserializeBuildError).collect(Collectors.toList())
                : List.of();

        String finishedAt = (String) map.get("finishedAt");

        return new PlanResult(
                planId,
                description,
                status,
                projectDir,
                tool,
                totalDur,
                totalDurFmt,
                steps,
                summary,
                errors,
                finishedAt != null ? java.time.Instant.parse(finishedAt) : null);
    }

    @SuppressWarnings("unchecked")
    private StepResult deserializeStepResult(Map<String, Object> m) {
        Map<String, Object> tsRaw = (Map<String, Object>) m.get("testSummary");
        StepResult.TestSummary ts = tsRaw != null ? deserializeTestSummary(tsRaw) : null;

        List<Map<String, Object>> errorsRaw = (List<Map<String, Object>>) m.get("errors");
        List<StepResult.BuildError> errors = errorsRaw != null
                ? errorsRaw.stream().map(this::deserializeBuildError).collect(Collectors.toList())
                : List.of();

        String startedAt = (String) m.get("startedAt");
        String finishedAt = (String) m.get("finishedAt");

        return new StepResult(
                (String) m.get("id"),
                (String) m.get("label"),
                (String) m.get("status"),
                ((Number) m.getOrDefault("durationSeconds", 0.0)).doubleValue(),
                Boolean.TRUE.equals(m.getOrDefault("success", false)),
                (String) m.getOrDefault("output", ""),
                ts,
                errors,
                ((Number) m.getOrDefault("retryAttempted", 0)).intValue(),
                startedAt != null ? java.time.Instant.parse(startedAt) : null,
                finishedAt != null ? java.time.Instant.parse(finishedAt) : null);
    }

    private StepResult.TestSummary deserializeTestSummary(Map<String, Object> m) {
        return new StepResult.TestSummary(
                ((Number) m.get("total")).intValue(),
                ((Number) m.get("passed")).intValue(),
                ((Number) m.get("failed")).intValue(),
                ((Number) m.get("errors")).intValue(),
                ((Number) m.get("skipped")).intValue());
    }

    private StepResult.BuildError deserializeBuildError(Map<String, Object> m) {
        return new StepResult.BuildError(
                (String) m.getOrDefault("file", ""),
                ((Number) m.getOrDefault("line", 0)).intValue(),
                (String) m.getOrDefault("severity", "ERROR"),
                (String) m.getOrDefault("message", ""));
    }

    private PlanSummary deserializeSummary(Map<String, Object> m) {
        return new PlanSummary(
                ((Number) m.get("total")).intValue(),
                ((Number) m.get("completed")).intValue(),
                ((Number) m.get("failed")).intValue(),
                ((Number) m.get("skipped")).intValue(),
                ((Number) m.getOrDefault("errorCount", 0)).intValue(),
                ((Number) m.getOrDefault("warningCount", 0)).intValue());
    }

    // ─── Minimal JSON parser for deserialization ────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSimpleJson(String json) {
        // Use JsonUtils to parse — it returns a Map
        // Since JsonUtils.toJson produces valid JSON, we parse by assuming
        // the structure is a flat or nested map. For simplicity, we use a basic approach.
        Map<String, Object> result = new LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1);
        }
        // Simple key-value extraction for top-level fields
        // This handles the JSON produced by the serializer
        int depth = 0;
        boolean inKey = false;
        boolean inValue = false;
        boolean inString = false;
        boolean escaped = false;
        char quote = '"';

        // Use a simpler approach: leverage the fact that our serialized JSON has
        // known structure with primitive values at top level and arrays/objects as values
        // We'll do a quick recursive descent parser
        try {
            return parseMap(json);
        } catch (Exception e) {
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) return map;

        int i = 0;
        while (i < trimmed.length()) {
            // Skip whitespace and commas
            while (i < trimmed.length()
                    && (trimmed.charAt(i) == ' '
                            || trimmed.charAt(i) == ','
                            || trimmed.charAt(i) == '\n'
                            || trimmed.charAt(i) == '\r')) i++;
            if (i >= trimmed.length()) break;

            // Parse key
            if (trimmed.charAt(i) != '"') break;
            i++; // skip opening quote
            int keyStart = i;
            while (i < trimmed.length() && trimmed.charAt(i) != '"') {
                if (trimmed.charAt(i) == '\\') i++; // skip escaped char
                i++;
            }
            String key = trimmed.substring(keyStart, i);
            i++; // skip closing quote

            // Skip colon
            while (i < trimmed.length() && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == ':')) i++;

            // Parse value
            if (i >= trimmed.length()) break;

            char c = trimmed.charAt(i);
            Object val;
            if (c == '"') {
                // String value
                i++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (i < trimmed.length()) {
                    if (trimmed.charAt(i) == '\\') {
                        sb.append(trimmed.charAt(i + 1));
                        i += 2;
                    } else if (trimmed.charAt(i) == '"') {
                        i++;
                        break;
                    } else {
                        sb.append(trimmed.charAt(i));
                        i++;
                    }
                }
                val = sb.toString();
            } else if (c == '{') {
                // Nested object — find matching closing brace
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
                    if (trimmed.charAt(i) == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                        // skip string content to avoid counting braces inside strings
                    }
                    i++;
                }
                val = parseMap(trimmed.substring(start, i));
            } else if (c == '[') {
                // Array
                int braceCount = 0;
                int start = i;
                while (i < trimmed.length()) {
                    if (trimmed.charAt(i) == '[') braceCount++;
                    if (trimmed.charAt(i) == ']') {
                        braceCount--;
                        if (braceCount == 0) {
                            i++;
                            break;
                        }
                    }
                    i++;
                }
                val = parseArray(trimmed.substring(start, i));
            } else {
                // Number or boolean
                StringBuilder sb = new StringBuilder();
                while (i < trimmed.length()
                        && trimmed.charAt(i) != ','
                        && trimmed.charAt(i) != '}'
                        && trimmed.charAt(i) != ']'
                        && trimmed.charAt(i) != ' ') {
                    sb.append(trimmed.charAt(i));
                    i++;
                }
                String raw = sb.toString();
                if ("true".equals(raw)) val = true;
                else if ("false".equals(raw)) val = false;
                else if ("null".equals(raw)) val = null;
                else if (raw.contains(".")) val = Double.parseDouble(raw);
                else val = Integer.parseInt(raw);
            }

            map.put(key, val);
        }

        return map;
    }

    private List<Object> parseArray(String json) {
        List<Object> list = new java.util.ArrayList<>();
        String trimmed = json.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) return list;

        int i = 0;
        while (i < trimmed.length()) {
            while (i < trimmed.length()
                    && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == ',' || trimmed.charAt(i) == '\n')) i++;
            if (i >= trimmed.length()) break;

            char c = trimmed.charAt(i);
            if (c == '{') {
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
                    i++;
                }
                list.add(parseMap(trimmed.substring(start, i)));
            } else if (c == '"') {
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < trimmed.length() && trimmed.charAt(i) != '"') {
                    if (trimmed.charAt(i) == '\\') {
                        sb.append(trimmed.charAt(i + 1));
                        i += 2;
                    } else {
                        sb.append(trimmed.charAt(i));
                        i++;
                    }
                }
                i++; // skip closing quote
                list.add(sb.toString());
            } else {
                i++;
            }
        }
        return list;
    }
}
