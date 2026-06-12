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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * MCP service that provides asynchronous build execution via the
 * MCP tasks extension pattern.
 * <p>
 * Long-running build operations (Maven, Gradle, SBT) are executed
 * asynchronously. The client receives an immediate task handle and
 * can poll for progress, stream output, or cancel the build.
 * <p>
 * Task lifecycle: {@code queued -> running -> completed / failed / cancelled}
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code execute_build_async} — start a build, return a task handle immediately</li>
 *   <li>{@code get_build_task} — poll task status, progress, and partial output</li>
 *   <li>{@code cancel_build_task} — cancel a running build task</li>
 *   <li>{@code list_build_tasks} — list all active and recent tasks</li>
 * </ul>
 */
@Service
public class AsyncBuildService {

    private final BuildToolProvider toolProvider;
    private final Map<String, BuildOutputParser> outputParsers;
    private final ConcurrentHashMap<String, BuildTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AsyncBuildService(BuildToolProvider toolProvider) {
        this.toolProvider = toolProvider;
        this.outputParsers = new LinkedHashMap<>();
        this.outputParsers.put("maven", new MavenOutputParser());
        this.outputParsers.put("gradle", new GradleOutputParser());
        this.outputParsers.put("sbt", new SbtOutputParser());
    }

    /**
     * Start an asynchronous build and return a task handle immediately.
     * <p>
     * The build runs in the background. Use {@link #getBuildTask} to poll for
     * status, progress, and results. Use {@link #cancelBuildTask} to cancel.
     */
    @Tool(name = "execute_build_async",
          description = "Start an async build and return a task handle immediately. " +
                        "Use this for long-running builds (30s+) instead of execute_build_command. " +
                        "Returns a task JSON with {taskId, status:\"queued\"}. " +
                        "Poll with get_build_task to track progress, stream output, and get results. " +
                        "Supports Maven, Gradle, and SBT.")
    public String executeBuildAsync(
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
            @ToolParam(required = false, description = "Build tool name. Omit to auto-detect.")
            String buildToolName,
            @ToolParam(required = false, description = "Path to build tool installation.")
            String buildToolHome,
            @ToolParam(required = true, description = "Path to the project directory")
            String projectDir,
            @ToolParam(required = true, description = "Build command to execute (e.g., 'clean compile')")
            String command) {

        // --- Input validation (aligned with BuildToolsService) ---
        if (command == null || command.trim().isEmpty()) {
            return JsonUtils.errorJson("Command cannot be null or empty.");
        }
        if (command.length() > 500) {
            return JsonUtils.errorJson("Command too long (max 500 characters).");
        }

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

        BuildTool tool;
        try {
            tool = toolProvider.resolve(buildToolName, validatedProject);
        } catch (IllegalArgumentException e) {
            return JsonUtils.errorJson(e.getMessage());
        }

        // --- Create task ---
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        BuildTask task = new BuildTask(
                taskId, tool.getName(), command, validatedProject.toString(),
                validatedHome, Instant.now());

        tasks.put(taskId, task);

        // --- Start async execution ---
        Future<?> future = executor.submit(() -> executeTask(task, tool));
        task.future = future;

        // --- Return immediate response ---
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("status", "queued");
        result.put("tool", tool.getName());
        result.put("command", command);
        result.put("projectDir", validatedProject.toString());
        result.put("createdAt", task.createdAt.toString());
        result.put("message", "Build queued. Poll with get_build_task(taskId=\"" + taskId +
                "\") to track progress.");

        return JsonUtils.toJson(result);
    }

    /**
     * Poll a build task for its current status, progress, and partial output.
     */
    @Tool(name = "get_build_task",
          description = "Get the current status, progress, and partial output of an async build task. " +
                        "Returns JSON with {taskId, status, progress, output (partial), duration, " +
                        "phaseProgress, result (when completed)}. " +
                        "Status values: queued, running, completed, failed, cancelled.")
    public String getBuildTask(
            @ToolParam(required = true, description = "Task ID returned by execute_build_async")
            String taskId) {

        BuildTask task = tasks.get(taskId);
        if (task == null) {
            return JsonUtils.errorJson("Task not found: " + taskId +
                    ". Tasks may expire after 1 hour. Use list_build_tasks to see active tasks.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.taskId);
        result.put("status", task.status);
        result.put("tool", task.toolName);
        result.put("command", task.command);
        result.put("projectDir", task.projectDir);
        result.put("createdAt", task.createdAt.toString());

        Duration elapsed = Duration.between(task.createdAt, Instant.now());
        result.put("elapsedSeconds", Math.round(elapsed.toMillis() / 100.0) / 10.0);
        result.put("elapsedFormatted", formatDuration(elapsed));

        // Progress info
        if (task.phaseProgress != null && !task.phaseProgress.isEmpty()) {
            result.put("phaseProgress", task.phaseProgress);
        }

        // Partial output snapshot (last 200 lines)
        synchronized (task.outputLock) {
            String output = task.output.toString();
            if (!output.isEmpty()) {
                String[] lines = output.split("\n");
                int start = Math.max(0, lines.length - 200);
                String[] recent = Arrays.copyOfRange(lines, start, lines.length);
                result.put("outputLines", recent.length);
                result.put("output", String.join("\n", recent));
            }
        }

        // Final result
        if ("completed".equals(task.status) || "failed".equals(task.status)) {
            if (task.completedAt != null) {
                result.put("completedAt", task.completedAt.toString());
                Duration total = Duration.between(task.createdAt, task.completedAt);
                result.put("totalDurationSeconds",
                        Math.round(total.toMillis() / 100.0) / 10.0);
                result.put("totalDurationFormatted", formatDuration(total));
            }
            if (task.exitCode != null) {
                result.put("exitCode", task.exitCode);
            }
            if (task.errorMessage != null) {
                result.put("error", task.errorMessage);
            }
            // Include parsed build output on completion
            if (task.parsedResult != null) {
                result.put("result", task.parsedResult);
            }
        } else if ("cancelled".equals(task.status)) {
            if (task.completedAt != null) {
                result.put("cancelledAt", task.completedAt.toString());
            }
        }

        return JsonUtils.toJson(result);
    }

    /**
     * Cancel a running build task by killing the underlying process.
     */
    @Tool(name = "cancel_build_task",
          description = "Cancel a running async build task. Kills the underlying build process " +
                        "and marks the task as cancelled. Has no effect on already-completed tasks. " +
                        "Returns JSON with {taskId, status, cancelled}.")
    public String cancelBuildTask(
            @ToolParam(required = true, description = "Task ID to cancel")
            String taskId) {

        BuildTask task = tasks.get(taskId);
        if (task == null) {
            return JsonUtils.errorJson("Task not found: " + taskId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);

        if (!"running".equals(task.status) && !"queued".equals(task.status)) {
            result.put("status", task.status);
            result.put("cancelled", false);
            result.put("message", "Task is already in terminal state: " + task.status);
            return JsonUtils.toJson(result);
        }

        // Cancel the future (interrupts the thread)
        if (task.future != null) {
            task.future.cancel(true);
        }

        // Kill the underlying process
        if (task.buildProcess != null && task.buildProcess.isAlive()) {
            task.buildProcess.destroyForcibly();
        }

        task.status = "cancelled";
        task.completedAt = Instant.now();

        result.put("status", "cancelled");
        result.put("cancelled", true);
        result.put("message", "Build task cancelled successfully.");

        return JsonUtils.toJson(result);
    }

    /**
     * List all active and recent build tasks.
     */
    @Tool(name = "list_build_tasks",
          description = "List all async build tasks. Returns JSON with {activeCount, completedCount, " +
                        "tasks: [{taskId, status, tool, command, elapsed}]}. " +
                        "Active tasks include queued and running. Completed tasks are kept for 1 hour.")
    public String listBuildTasks() {
        List<Map<String, Object>> taskList = new ArrayList<>();
        Instant now = Instant.now();

        for (BuildTask task : tasks.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("taskId", task.taskId);
            entry.put("status", task.status);
            entry.put("tool", task.toolName);
            entry.put("command", task.command);
            entry.put("projectDir", task.projectDir);
            Duration elapsed = Duration.between(task.createdAt, now);
            entry.put("elapsedSeconds", Math.round(elapsed.toMillis() / 100.0) / 10.0);
            taskList.add(entry);
        }

        long activeCount = taskList.stream()
                .filter(t -> "queued".equals(t.get("status")) || "running".equals(t.get("status")))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeCount", (int) activeCount);
        result.put("completedCount", taskList.size() - (int) activeCount);
        result.put("totalCount", taskList.size());
        result.put("tasks", taskList);

        return JsonUtils.toJson(result);
    }

    // ─── Internal execution ─────────────────────────────────────────────

    private void executeTask(BuildTask task, BuildTool tool) {
        task.status = "running";
        Instant start = Instant.now();

        try {
            switch (tool.getName()) {
                case "maven" -> executeMavenAsync(task);
                case "gradle" -> executeGradleAsync(task);
                case "sbt" -> executeSbtAsync(task);
                default -> executeGenericAsync(task, tool);
            }

            task.exitCode = 0;
            task.status = "completed";
        } catch (CancellationException e) {
            task.status = "cancelled";
            task.errorMessage = "Build cancelled by user";
        } catch (Exception e) {
            task.exitCode = 1;
            task.status = "failed";
            task.errorMessage = e.getMessage();
            synchronized (task.outputLock) {
                task.output.append("ERROR: ").append(e.getMessage()).append("\n");
            }
        } finally {
            task.completedAt = Instant.now();
            task.buildProcess = null; // Release process reference

            // Parse output for completed/failed tasks
            if (("completed".equals(task.status) || "failed".equals(task.status))
                    && task.exitCode != null) {
                try {
                    BuildOutputParser parser = outputParsers.getOrDefault(
                            task.toolName, outputParsers.get("maven"));
                    String output;
                    synchronized (task.outputLock) {
                        output = task.output.toString();
                    }
                    task.parsedResult = parser.parse(output, task.exitCode, task.command);
                } catch (Exception ignored) {
                    // Parsing is best-effort
                }
            }

            // Persist task summary to .buildtools/tasks/
            persistTaskSummary(task);
        }
    }

    private void executeMavenAsync(BuildTask task) throws Exception {
        if (task.buildToolHome == null || task.buildToolHome.isBlank()) {
            throw new IllegalArgumentException("Maven requires buildToolHome for async execution.");
        }

        String[] commands = MavenInvoker.getCommands(task.command);
        if (commands.length == 0) {
            throw new IllegalArgumentException("No valid Maven commands in: " + task.command);
        }

        MavenInvoker.MavenProcessExecution exec = MavenInvoker.executeWithProcessCapture(
                task.buildToolHome, commands, task.projectDir);
        task.buildProcess = exec.process();

        // Wait for process completion, collecting phase progress
        int exitCode = exec.process().waitFor();
        exec.outputCollector().join(5000); // Wait for collector thread to finish

        synchronized (task.outputLock) {
            task.output.append(exec.output().toString());
        }

        if (exitCode != 0) {
            String errOutput = exec.errors().toString();
            if (!errOutput.isEmpty()) {
                synchronized (task.outputLock) {
                    task.output.append(errOutput);
                }
            }
            throw new RuntimeException("Maven exited with code " + exitCode +
                    (errOutput.isEmpty() ? "" : ": " + errOutput));
        }

        // Extract phase progress from output
        extractPhaseProgress(task, exec.output().toString(), "maven");
    }

    private void executeGradleAsync(BuildTask task) throws Exception {
        String[] tokens = GradleBuildTool.parseCommandTokens(task.command);

        Path projectPath = Path.of(task.projectDir);
        String executable = GradleBuildTool.resolveGradleExecutable(
                task.buildToolHome, task.projectDir);

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.addAll(Arrays.asList(tokens));
        cmdList.add("--no-daemon");
        cmdList.add("--console=plain");

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.directory(new File(task.projectDir));
        Process process = pb.start();
        task.buildProcess = process;

        readProcessOutput(task, process);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Gradle exited with code " + exitCode);
        }

        extractPhaseProgress(task, task.output.toString(), "gradle");
    }

    private void executeSbtAsync(BuildTask task) throws Exception {
        String[] tokens = SbtBuildTool.parseCommandTokens(task.command);

        String executable = SbtBuildTool.resolveSbtExecutable(
                task.buildToolHome, task.projectDir);

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.add("--no-colors");
        cmdList.addAll(Arrays.asList(tokens));

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.directory(new File(task.projectDir));
        Process process = pb.start();
        task.buildProcess = process;

        readProcessOutput(task, process);

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("sbt exited with code " + exitCode);
        }
    }

    private void executeGenericAsync(BuildTask task, BuildTool tool) throws Exception {
        // Fallback: use synchronous executeCommand in a thread
        String output = tool.executeCommand(task.buildToolHome,
                task.projectDir, task.command);
        synchronized (task.outputLock) {
            task.output.append(output);
        }
    }

    private void readProcessOutput(BuildTask task, Process process) {
        Thread outThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (task.outputLock) {
                        task.output.append(line).append("\n");
                    }
                }
            } catch (IOException ignored) {
                // Process destroyed
            }
        }, "async-stdout-" + task.taskId);

        Thread errThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (task.outputLock) {
                        task.output.append(line).append("\n");
                    }
                }
            } catch (IOException ignored) {
                // Process destroyed
            }
        }, "async-stderr-" + task.taskId);

        outThread.start();
        errThread.start();
        try {
            outThread.join();
            errThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void extractPhaseProgress(BuildTask task, String output, String toolName) {
        List<Map<String, Object>> phases = new ArrayList<>();
        if ("maven".equals(toolName)) {
            var pattern = java.util.regex.Pattern.compile(
                    "\\[INFO\\] --- ([a-zA-Z0-9._-]+):([0-9.]+):([a-zA-Z-]+)\\s.*---");
            var matcher = pattern.matcher(output);
            while (matcher.find()) {
                Map<String, Object> phase = new LinkedHashMap<>();
                phase.put("plugin", matcher.group(1));
                phase.put("goal", matcher.group(3));
                phase.put("name", matcher.group(1) + ":" + matcher.group(3));
                phases.add(phase);
            }
        } else if ("gradle".equals(toolName)) {
            var pattern = java.util.regex.Pattern.compile(
                    "> Task ([\\w:]+)\\s*(UP-TO-DATE|SKIPPED|FROM-CACHE|SUCCESS|FAILED)?");
            var matcher = pattern.matcher(output);
            while (matcher.find()) {
                Map<String, Object> phase = new LinkedHashMap<>();
                phase.put("task", matcher.group(1));
                String outcome = matcher.group(2);
                phase.put("outcome", outcome != null ? outcome : "EXECUTED");
                phases.add(phase);
            }
        }
        if (!phases.isEmpty()) {
            task.phaseProgress = phases;
        }
    }

    // ─── Persistence ────────────────────────────────────────────────────

    private void persistTaskSummary(BuildTask task) {
        try {
            Path tasksDir = Path.of(task.projectDir).resolve(".buildtools/tasks");
            Files.createDirectories(tasksDir);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("taskId", task.taskId);
            summary.put("status", task.status);
            summary.put("tool", task.toolName);
            summary.put("command", task.command);
            summary.put("createdAt", task.createdAt.toString());
            if (task.completedAt != null) {
                summary.put("completedAt", task.completedAt.toString());
            }
            if (task.exitCode != null) {
                summary.put("exitCode", task.exitCode);
            }
            if (task.errorMessage != null) {
                summary.put("error", task.errorMessage);
            }
            if (task.phaseProgress != null) {
                summary.put("phaseProgress", task.phaseProgress);
            }

            Path taskFile = tasksDir.resolve(task.taskId + ".json");
            Files.writeString(taskFile, JsonUtils.toJson(summary),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // Non-critical
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static String formatDuration(Duration d) {
        long totalSec = d.getSeconds();
        if (totalSec < 60) return totalSec + "s";
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min < 60) return min + "m " + sec + "s";
        long hr = min / 60;
        min = min % 60;
        return hr + "h " + min + "m " + sec + "s";
    }

    // ─── Task state ─────────────────────────────────────────────────────

    static class BuildTask {
        final String taskId;
        final String toolName;
        final String command;
        final String projectDir;
        final String buildToolHome;
        final Instant createdAt;

        volatile String status = "queued";
        volatile Instant completedAt;
        volatile Integer exitCode;
        volatile String errorMessage;
        volatile Process buildProcess;
        volatile Future<?> future;
        volatile List<Map<String, Object>> phaseProgress;
        volatile Map<String, Object> parsedResult;

        final StringBuilder output = new StringBuilder();
        final Object outputLock = new Object();

        BuildTask(String taskId, String toolName, String command, String projectDir,
                  String buildToolHome, Instant createdAt) {
            this.taskId = taskId;
            this.toolName = toolName;
            this.command = command;
            this.projectDir = projectDir;
            this.buildToolHome = buildToolHome;
            this.createdAt = createdAt;
        }
    }
}
