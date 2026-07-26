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

import com.pragmatik.buildtools.build.BuildTool;
import com.pragmatik.buildtools.build.BuildToolProvider;
import com.pragmatik.buildtools.build.SyncProcessRunner.ExecutionTimeoutException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Sequential executor for build plans.
 *
 * <p>Executes plan steps one at a time respecting dependency ordering.
 * Supports cancellation, configurable failure handling, and retry logic.
 * Each step is delegated to the appropriate BuildTool via BuildToolProvider.
 */
public class PlanExecutionEngine {

    private final BuildToolProvider provider;
    private final ConcurrentHashMap<String, PlanExecution> activeExecutions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PlanResult> completedResults = new ConcurrentHashMap<>();

    public PlanExecutionEngine(BuildToolProvider provider) {
        this.provider = provider;
    }

    /**
     * Execute a build plan sequentially.
     *
     * @param plan the build plan to execute
     * @return aggregated PlanResult
     */
    public PlanResult executePlan(BuildPlan plan) {
        String planId = plan.planId();

        PlanExecution execution = new PlanExecution(planId);
        activeExecutions.put(planId, execution);
        completedResults.remove(planId);

        Instant planStart = Instant.now();
        List<StepResult> stepResults = new ArrayList<>();
        List<StepResult.BuildError> allErrors = new ArrayList<>();
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        boolean shouldStop = false;

        // Build step map for dependency resolution
        Map<String, PlanStep> stepMap = plan.steps().stream().collect(Collectors.toMap(PlanStep::id, s -> s));

        // Topological order: already ordered in the plan, but verify dependencies
        List<PlanStep> orderedSteps = resolveOrder(plan.steps(), stepMap);

        for (PlanStep step : orderedSteps) {
            // Check for cancellation
            if (execution.isCancelled()) {
                // Mark remaining as skipped
                for (PlanStep remaining : orderedSteps.subList(stepResults.size(), orderedSteps.size())) {
                    stepResults.add(new StepResult(
                            remaining.id(),
                            remaining.label(),
                            "skipped",
                            0,
                            false,
                            "",
                            null,
                            List.of(),
                            0,
                            null,
                            null));
                    skipped++;
                }
                break;
            }

            // Check if we should stop due to a previous failure
            if (shouldStop && !"continue".equals(step.onFailure()) && !"continue".equals(plan.errorHandling())) {
                stepResults.add(new StepResult(
                        step.id(), step.label(), "skipped", 0, false, "", null, List.of(), 0, null, null));
                skipped++;
                continue;
            }

            // Check dependency completion
            boolean depsMet = true;
            for (String depId : step.dependsOn()) {
                boolean depCompleted =
                        stepResults.stream().anyMatch(sr -> sr.id().equals(depId) && sr.success());
                if (!depCompleted) {
                    depsMet = false;
                    break;
                }
            }
            if (!depsMet) {
                stepResults.add(new StepResult(
                        step.id(), step.label(), "skipped", 0, false, "", null, List.of(), 0, null, null));
                skipped++;
                continue;
            }

            // Execute the step
            Instant stepStart = Instant.now();
            String status = "running";
            boolean success = false;
            String output = "";
            int retryAttempted = 0;
            List<StepResult.BuildError> stepErrors = new ArrayList<>();

            int maxAttempts = Math.min(Math.max(step.retryCount(), 0), 3) + 1;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                // Check for cancellation between retry attempts
                if (execution.isCancelled()) {
                    status = "cancelled";
                    break;
                }
                if (attempt > 1) retryAttempted++;
                try {
                    output = executeStepCommand(plan, step);
                    success = true;
                    status = "completed";
                    break;
                } catch (ExecutionTimeoutException e) {
                    stepErrors.add(new StepResult.BuildError(
                            "", 0, "ERROR", "Step timed out after " + step.timeoutSeconds() + "s"));
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    stepErrors.add(new StepResult.BuildError("", 0, "ERROR", e.getMessage()));
                    status = "failed";
                    success = false;
                    break; // Don't retry invalid args
                } catch (Exception e) {
                    stepErrors.add(new StepResult.BuildError("", 0, "ERROR", e.getMessage()));
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                }
            }

            if (!success && status.equals("running")) {
                status = "failed";
            }

            Instant stepEnd = Instant.now();
            double duration = Duration.between(stepStart, stepEnd).toMillis() / 1000.0;

            StepResult stepResult = new StepResult(
                    step.id(),
                    step.label(),
                    status,
                    duration,
                    success,
                    output != null && output.length() > 10240 ? output.substring(0, 10240) : output,
                    null,
                    stepErrors,
                    retryAttempted,
                    stepStart,
                    stepEnd);
            stepResults.add(stepResult);
            allErrors.addAll(stepErrors);

            if (success) {
                completed++;
            } else {
                failed++;
                String onFailure = step.onFailure() != null ? step.onFailure() : plan.errorHandling();
                if ("stop".equals(onFailure)) {
                    shouldStop = true;
                    break;
                } else if ("skipRemaining".equals(onFailure)) {
                    // Mark remaining as skipped
                    for (PlanStep remaining : orderedSteps.subList(stepResults.size(), orderedSteps.size())) {
                        stepResults.add(new StepResult(
                                remaining.id(),
                                remaining.label(),
                                "skipped",
                                0,
                                false,
                                "",
                                null,
                                List.of(),
                                0,
                                null,
                                null));
                        skipped++;
                    }
                    break;
                }
                // "continue" — keep going
            }
        }

        // Update remaining if cancelled mid-execution
        if (execution.isCancelled()) {
            for (PlanStep remaining : orderedSteps.subList(stepResults.size(), orderedSteps.size())) {
                stepResults.add(new StepResult(
                        remaining.id(), remaining.label(), "skipped", 0, false, "", null, List.of(), 0, null, null));
                skipped++;
            }
        }

        Instant planEnd = Instant.now();
        double totalDuration = Duration.between(planStart, planEnd).toMillis() / 1000.0;

        String overallStatus = execution.isCancelled() ? "cancelled" : failed > 0 ? "failed" : "completed";

        int totalSteps = plan.steps().size();
        PlanSummary summary = new PlanSummary(
                totalSteps,
                completed,
                failed,
                skipped,
                (int) allErrors.stream()
                        .filter(e -> "ERROR".equals(e.severity()))
                        .count(),
                (int) allErrors.stream()
                        .filter(e -> "WARNING".equals(e.severity()))
                        .count());

        // Determine tool name
        String toolName = plan.buildToolName() != null ? plan.buildToolName() : "auto-detected";
        if (toolName == null) toolName = "unknown";

        PlanResult result = new PlanResult(
                planId,
                plan.description(),
                overallStatus,
                plan.projectDir(),
                toolName,
                totalDuration,
                formatDuration(totalDuration),
                stepResults,
                summary,
                allErrors,
                planEnd);

        completedResults.put(planId, result);
        activeExecutions.remove(planId);
        return result;
    }

    /**
     * Cancel a running plan execution.
     */
    public void cancelPlan(String planId) {
        PlanExecution execution = activeExecutions.get(planId);
        if (execution != null) {
            execution.cancel();
        }
    }

    /**
     * Get the current status/result of a plan.
     *
     * @return PlanResult if completed or running, null if not found
     */
    public PlanResult getPlanStatus(String planId) {
        PlanResult completed = completedResults.get(planId);
        if (completed != null) {
            return completed;
        }
        PlanExecution execution = activeExecutions.get(planId);
        if (execution != null) {
            // Return a "running" status
            return new PlanResult(
                    planId,
                    "",
                    "running",
                    "",
                    "",
                    0,
                    "",
                    List.of(),
                    new PlanSummary(0, 0, 0, 0, 0, 0),
                    List.of(),
                    null);
        }
        return null;
    }

    // ─── Private helpers ────────────────────────────────────────────

    private String executeStepCommand(BuildPlan plan, PlanStep step) {
        String projectDir = plan.projectDir();
        String buildToolName = plan.buildToolName();
        String buildToolHome = plan.buildToolHome();

        // Validate project directory
        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve project directory: " + projectDir, e);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Project directory not found: " + projectDir);
        }

        // Resolve build tool
        BuildTool tool = provider.resolve(buildToolName, dir);
        return tool.executeCommand(buildToolHome, projectDir, step.command());
    }

    private List<PlanStep> resolveOrder(List<PlanStep> steps, Map<String, PlanStep> stepMap) {
        // Simple topological sort — steps are already ordered, but verify
        List<PlanStep> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        for (PlanStep step : steps) {
            visit(step, stepMap, visited, ordered, new HashSet<>());
        }

        return ordered;
    }

    private void visit(
            PlanStep step,
            Map<String, PlanStep> stepMap,
            Set<String> visited,
            List<PlanStep> ordered,
            Set<String> visiting) {
        if (visited.contains(step.id())) return;
        if (visiting.contains(step.id())) {
            throw new IllegalArgumentException("Circular dependency detected involving step: " + step.id());
        }
        visiting.add(step.id());

        for (String depId : step.dependsOn()) {
            PlanStep dep = stepMap.get(depId);
            if (dep != null) {
                visit(dep, stepMap, visited, ordered, visiting);
            }
        }

        visited.add(step.id());
        ordered.add(step);
    }

    private String formatDuration(double seconds) {
        if (seconds < 60) {
            return String.format("%.1fs", seconds);
        } else if (seconds < 3600) {
            int min = (int) seconds / 60;
            double sec = seconds % 60;
            return String.format("%dm %.1fs", min, sec);
        } else {
            int hours = (int) seconds / 3600;
            int min = (int) (seconds % 3600) / 60;
            return String.format("%dh %dm", hours, min);
        }
    }

    // ─── Internal execution state ───────────────────────────────────

    private static class PlanExecution {
        private final String planId;
        private volatile boolean cancelled = false;

        PlanExecution(String planId) {
            this.planId = planId;
        }

        void cancel() {
            this.cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }
    }
}
