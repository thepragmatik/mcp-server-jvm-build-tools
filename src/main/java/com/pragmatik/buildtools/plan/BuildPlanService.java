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
import com.pragmatik.buildtools.build.BuildToolsService;
import com.pragmatik.buildtools.tool.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tool service for build plan authoring and execution.
 *
 * <p>Exposes tools for creating, executing, and managing multi-step build plans.
 * Plans are generated from natural language descriptions by extracting
 * lifecycle keywords and ordering them by convention.
 */
@Service
public class BuildPlanService {

    private static final Set<String> KNOWN_KEYWORDS =
            Set.of("clean", "compile", "build", "test", "package", "install", "deploy", "validate");

    private static final List<String> PHASE_ORDER =
            List.of("clean", "validate", "compile", "build", "test", "package", "install", "deploy");

    private final BuildToolsService buildToolsService;
    private final BuildToolProvider buildToolProvider;
    private final ConcurrentHashMap<String, PlanData> planStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlanResult> resultStore = new ConcurrentHashMap<>();

    public BuildPlanService(BuildToolsService buildToolsService, BuildToolProvider buildToolProvider) {
        this.buildToolsService = buildToolsService;
        this.buildToolProvider = buildToolProvider;
    }

    /**
     * Parse a natural language description into ordered build steps.
     *
     * @param description the natural language description
     * @return ordered list of PlanSteps, or empty list if no keywords found
     */
    public List<PlanStep> parseDescription(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }

        Set<String> keywords = new LinkedHashSet<>();
        String[] words = description.toLowerCase().split("[\\s,;.]+");
        for (String word : words) {
            if (KNOWN_KEYWORDS.contains(word)) {
                keywords.add(word);
            }
        }

        if (keywords.isEmpty()) {
            return List.of();
        }

        // Order by lifecycle phase
        List<String> ordered = new ArrayList<>(keywords);
        ordered.sort(Comparator.comparingInt(k -> {
            int idx = PHASE_ORDER.indexOf(k);
            return idx >= 0 ? idx : Integer.MAX_VALUE;
        }));

        // Build steps
        List<PlanStep> steps = new ArrayList<>();
        String prevStepId = null;
        int stepNum = 1;

        for (String keyword : ordered) {
            String stepId = "step-" + stepNum;
            String label = toLabel(keyword);
            List<String> dependsOn = prevStepId != null ? List.of(prevStepId) : List.of();

            steps.add(new PlanStep(stepId, label, keyword, dependsOn, 300, "stop", true, 0));
            prevStepId = stepId;
            stepNum++;
        }

        return steps;
    }

    /**
     * Create a build plan from a natural language description.
     *
     * @param description the build workflow description
     * @param projectDir  the project directory
     * @return JSON response with plan details or error
     */
    @Tool(
            name = "create_build_plan",
            description = "Create a build plan from a natural language description. "
                    + "Returns a JSON plan with ordered steps. "
                    + "The plan is stored and can be executed later with execute_build_plan.")
    public String createBuildPlan(
            @ToolParam(required = true, description = "Natural language description of the build workflow")
                    String description,
            @ToolParam(required = true, description = "Project directory path") String projectDir) {

        if (description == null || description.isBlank()) {
            return JsonUtils.errorJson("Description cannot be null or empty");
        }

        List<PlanStep> steps = parseDescription(description);

        if (steps.isEmpty()) {
            return JsonUtils.errorJson("No recognizable build steps found in description");
        }

        // Validate project directory
        Path dir = Path.of(projectDir);
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory not found: " + projectDir);
        }

        // Generate plan ID
        String planId = UUID.randomUUID().toString();

        // Store the plan
        PlanData data = new PlanData(planId, description, projectDir, steps, Instant.now());
        planStore.put(planId, data);

        // Build JSON response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("planId", planId);
        response.put("description", description);
        response.put("projectDir", projectDir);
        response.put("stepCount", steps.size());

        List<Map<String, Object>> stepList = new ArrayList<>();
        for (PlanStep step : steps) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", step.id());
            sm.put("label", step.label());
            sm.put("command", step.command());
            sm.put("dependsOn", step.dependsOn());
            stepList.add(sm);
        }
        response.put("steps", stepList);

        return JsonUtils.toJson(response);
    }

    /**
     * Execute a previously created build plan.
     *
     * @param planId the plan ID
     * @return JSON result with execution status
     */
    @Tool(
            name = "execute_build_plan",
            description = "Execute a build plan. The plan must have been created "
                    + "with create_build_plan first. Returns execution results "
                    + "with per-step status, timing, and a summary.")
    public String executePlan(
            @ToolParam(required = true, description = "Plan ID returned by create_build_plan") String planId) {

        PlanData data = planStore.get(planId);
        if (data == null) {
            return JsonUtils.errorJson("Plan not found: " + planId);
        }

        // Execute each step sequentially
        List<Map<String, Object>> stepResults = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        boolean shouldStop = false;

        for (PlanStep step : data.steps()) {
            if (shouldStop) {
                skipped++;
                stepResults.add(stepResultMap(step, "skipped", 0, false, "", List.of()));
                continue;
            }

            long stepStart = System.currentTimeMillis();
            String output;
            boolean success;
            List<Map<String, Object>> errors = new ArrayList<>();

            try {
                // Execute via BuildToolsService
                output = buildToolsService.executeBuildCommand(null, null, data.projectDir(), step.command());
                success = true;
                completed++;
            } catch (Exception e) {
                output = e.getMessage();
                success = false;
                failed++;
                errors.add(errorMap(e.getMessage()));
                String onFail = step.onFailure() != null ? step.onFailure() : "stop";
                if ("stop".equals(onFail)) {
                    shouldStop = true;
                }
            }

            long duration = System.currentTimeMillis() - stepStart;
            stepResults.add(
                    stepResultMap(step, success ? "completed" : "failed", duration / 1000.0, success, output, errors));
        }

        String overallStatus = failed > 0 ? "failed" : "completed";

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", planId);
        response.put("description", data.description());
        response.put("status", overallStatus);
        response.put("projectDir", data.projectDir());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", data.steps().size());
        summary.put("completed", completed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        response.put("summary", summary);

        response.put("steps", stepResults);

        // Store result
        String resultJson = JsonUtils.toJson(response);

        return resultJson;
    }

    /**
     * Retrieve a stored build plan by ID.
     *
     * @param planId the plan ID
     * @return the BuildPlan, or null if not found
     */
    public BuildPlan getPlan(String planId) {
        PlanData data = planStore.get(planId);
        if (data == null) return null;
        return new BuildPlan(
                data.planId(),
                data.description(),
                data.projectDir(),
                null,
                null,
                data.steps(),
                "stop",
                data.createdAt(),
                3600);
    }

    // ─── Labels ─────────────────────────────────────────────────────

    private String toLabel(String keyword) {
        return switch (keyword) {
            case "clean" -> "Clean build artifacts";
            case "compile" -> "Compile source code";
            case "build" -> "Build the project";
            case "test" -> "Run tests";
            case "package" -> "Package artifacts";
            case "install" -> "Install to local repository";
            case "deploy" -> "Deploy to remote repository";
            case "validate" -> "Validate project structure";
            default -> "Execute " + keyword;
        };
    }

    // ─── Response helpers ───────────────────────────────────────────

    private Map<String, Object> stepResultMap(
            PlanStep step,
            String status,
            double duration,
            boolean success,
            String output,
            List<Map<String, Object>> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", step.id());
        m.put("label", step.label());
        m.put("command", step.command());
        m.put("status", status);
        m.put("durationSeconds", duration);
        m.put("success", success);
        if (output != null && !output.isEmpty()) {
            m.put("output", output.length() > 500 ? output.substring(0, 500) + "..." : output);
        }
        if (!errors.isEmpty()) {
            m.put("errors", errors);
        }
        return m;
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", "");
        m.put("line", 0);
        m.put("severity", "ERROR");
        m.put("message", message);
        return m;
    }

    // ─── Internal data carrier ──────────────────────────────────────

    private record PlanData(
            String planId, String description, String projectDir, List<PlanStep> steps, Instant createdAt) {}
}
