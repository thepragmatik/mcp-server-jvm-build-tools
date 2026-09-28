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

import com.pragmatik.buildtools.gradle.GradleBuildTool;
import com.pragmatik.buildtools.gradle.GradleOutputParser;
import com.pragmatik.buildtools.maven.MavenInvoker;
import com.pragmatik.buildtools.maven.MavenOutputParser;
import com.pragmatik.buildtools.sbt.SbtBuildTool;
import com.pragmatik.buildtools.sbt.SbtOutputParser;
import com.pragmatik.buildtools.tool.JsonUtils;
import com.pragmatik.buildtools.tracing.BuildTracer;
import com.pragmatik.buildtools.tracing.McpTraceContext;
import com.pragmatik.buildtools.tracing.TraceContextHolder;
import com.pragmatik.buildtools.tracing.TraceScope;
import com.pragmatik.buildtools.tracing.W3CTraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/**
 * Internal service that provides asynchronous build execution via the
 * MCP tasks extension pattern.
 * <p>
 * Long-running build operations (Maven, Gradle, SBT) are executed
 * asynchronously. The client receives an immediate task handle and
 * can poll for progress, stream output, or cancel the build.
 * <p>
 * Task lifecycle: {@code queued -> running -> completed / failed / cancelled}
 * <p>
 * Internal operations (not exposed as MCP tools):
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
    public String executeBuildAsync(
            @Schema(allowableValues = {"maven", "gradle", "sbt"}) String buildToolName,
            String buildToolHome,
            String projectDir,
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
                taskId, tool.getName(), command, validatedProject.toString(), validatedHome, Instant.now());
        // Capture any inbound trace context now (on the request thread) so the background
        // worker can continue the same distributed trace (SEP-414). Null when absent.
        task.inboundTrace = TraceContextHolder.inbound().orElse(null);

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
        result.put("message", "Build queued. Poll with get_build_task(taskId=\"" + taskId + "\") to track progress.");

        return JsonUtils.toJson(result);
    }

    /**
     * Poll a build task for its current status, progress, and partial output.
     */
    public String getBuildTask(String taskId) {

        BuildTask task = tasks.get(taskId);
        if (task == null) {
            return JsonUtils.errorJson("Task not found: " + taskId
                    + ". Tasks may expire after 1 hour. Use list_build_tasks to see active tasks.");
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
        String output = task.output.snapshot();
        if (!output.isEmpty()) {
            String[] lines = output.split("\n");
            int start = Math.max(0, lines.length - 200);
            String[] recent = Arrays.copyOfRange(lines, start, lines.length);
            result.put("outputLines", recent.length);
            result.put("output", String.join("\n", recent));
            result.put("outputTruncated", task.output.truncated());
        }

        // Final result
        if ("completed".equals(task.status) || "failed".equals(task.status)) {
            if (task.completedAt != null) {
                result.put("completedAt", task.completedAt.toString());
                Duration total = Duration.between(task.createdAt, task.completedAt);
                result.put("totalDurationSeconds", Math.round(total.toMillis() / 100.0) / 10.0);
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
    public String cancelBuildTask(String taskId) {

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

        // Publish cancellation intent before interrupting or terminating the
        // process so worker completion cannot race it back to failed/completed.
        task.cancellationRequested = true;

        // Cancel the future (interrupts the thread)
        if (task.future != null) {
            task.future.cancel(true);
        }

        // Kill the underlying process
        if (task.buildProcess != null && task.buildProcess.isAlive()) {
            SyncProcessRunner.terminateTree(task.buildProcess);
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
            // Re-establish the inbound trace context on this background thread and open a
            // span so the build subprocess (started below) is stamped with the trace and
            // appears under the originating trace as a single span tree (SEP-414).
            try (TraceScope inboundScope = McpTraceContext.activate(task.inboundTrace);
                    TraceScope span = BuildTracer.startSpan("execute_build_async")) {
                switch (tool.getName()) {
                    case "maven" -> executeMavenAsync(task);
                    case "gradle" -> executeGradleAsync(task);
                    case "sbt" -> executeSbtAsync(task);
                    default -> executeGenericAsync(task, tool);
                }
            }

            if (!task.cancellationRequested) {
                task.exitCode = 0;
                task.status = "completed";
            }
        } catch (InterruptedException e) {
            if (task.buildProcess != null) {
                SyncProcessRunner.terminateTree(task.buildProcess);
            }
            task.status = "cancelled";
            task.errorMessage = "Build cancelled by user";
            Thread.currentThread().interrupt();
        } catch (CancellationException e) {
            task.status = "cancelled";
            task.errorMessage = "Build cancelled by user";
        } catch (Exception e) {
            if (task.cancellationRequested) {
                task.status = "cancelled";
                task.errorMessage = "Build cancelled by user";
            } else {
                task.exitCode = 1;
                task.status = "failed";
                task.errorMessage = e.getMessage();
                task.output.appendLine("ERROR: " + e.getMessage());
            }
        } finally {
            task.completedAt = Instant.now();
            task.buildProcess = null; // Release process reference

            // Parse output for completed/failed tasks
            if (("completed".equals(task.status) || "failed".equals(task.status)) && task.exitCode != null) {
                try {
                    BuildOutputParser parser = outputParsers.getOrDefault(task.toolName, outputParsers.get("maven"));
                    task.parsedResult = parser.parse(task.output.snapshot(), task.exitCode, task.command);
                } catch (Exception e) {
                    System.err.println("[WARN] Async build output parsing failed");
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

        MavenInvoker.MavenProcessExecution exec =
                MavenInvoker.executeWithProcessCapture(task.buildToolHome, commands, task.projectDir);
        task.buildProcess = exec.process();

        // Wait for process completion, collecting phase progress
        int exitCode = awaitProcess(exec.process(), "maven-async");
        exec.outputCollector().join(5000); // Wait for collector thread to finish
        if (exec.outputCollector().isAlive()) {
            SyncProcessRunner.terminateTree(exec.process());
            throw new IOException("Maven output collector did not finish");
        }

        task.output.append(exec.output().snapshot());

        if (exitCode != 0) {
            String errOutput = exec.errors().toString();
            if (!errOutput.isEmpty()) {
                task.output.append(errOutput);
            }
            throw new RuntimeException(
                    "Maven exited with code " + exitCode + (errOutput.isEmpty() ? "" : ": " + errOutput));
        }

        // Extract phase progress from output
        extractPhaseProgress(task, exec.output().snapshot(), "maven");
    }

    private void executeGradleAsync(BuildTask task) throws Exception {
        String[] tokens = GradleBuildTool.parseCommandTokens(task.command);

        Path projectPath = Path.of(task.projectDir);
        String executable = GradleBuildTool.resolveGradleExecutable(task.buildToolHome, task.projectDir);

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.addAll(Arrays.asList(tokens));
        cmdList.add("--no-daemon");
        cmdList.add("--console=plain");

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.directory(new File(task.projectDir));
        TraceContextHolder.applyToEnvironment(pb.environment());
        Process process = pb.start();
        task.buildProcess = process;

        Thread[] readers = readProcessOutput(task, process);
        int exitCode = awaitProcess(process, "gradle-async");
        joinReaders(process, readers);
        if (exitCode != 0) {
            throw new RuntimeException("Gradle exited with code " + exitCode);
        }

        extractPhaseProgress(task, task.output.snapshot(), "gradle");
    }

    private void executeSbtAsync(BuildTask task) throws Exception {
        String[] tokens = SbtBuildTool.parseCommandTokens(task.command);

        String executable = SbtBuildTool.resolveSbtExecutable(task.buildToolHome, task.projectDir);

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.add("--no-colors");
        cmdList.addAll(Arrays.asList(tokens));

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.directory(new File(task.projectDir));
        TraceContextHolder.applyToEnvironment(pb.environment());
        Process process = pb.start();
        task.buildProcess = process;

        Thread[] readers = readProcessOutput(task, process);
        int exitCode = awaitProcess(process, "sbt-async");
        joinReaders(process, readers);
        if (exitCode != 0) {
            throw new RuntimeException("sbt exited with code " + exitCode);
        }
    }

    private void executeGenericAsync(BuildTask task, BuildTool tool) throws Exception {
        // Fallback: use synchronous executeCommand in a thread
        String output = tool.executeCommand(task.buildToolHome, task.projectDir, task.command);
        task.output.append(output);
    }

    private Thread[] readProcessOutput(BuildTask task, Process process) {
        return new Thread[] {
            SyncProcessRunner.drain(process.getInputStream(), task.output, "async-stdout-" + task.taskId),
            SyncProcessRunner.drain(process.getErrorStream(), task.output, "async-stderr-" + task.taskId)
        };
    }

    private static int awaitProcess(Process process, String label) throws InterruptedException {
        try {
            if (!process.waitFor(SyncProcessRunner.resolveTimeoutSeconds(), TimeUnit.SECONDS)) {
                SyncProcessRunner.terminateTree(process);
                throw new SyncProcessRunner.ExecutionTimeoutException("Process '" + label + "' timed out");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            SyncProcessRunner.terminateTree(process);
            throw e;
        }
    }

    private static void joinReaders(Process process, Thread[] readers) throws InterruptedException, IOException {
        for (Thread reader : readers) {
            try {
                reader.join(5000);
            } catch (InterruptedException e) {
                SyncProcessRunner.terminateTree(process);
                throw e;
            }
            if (reader.isAlive()) {
                SyncProcessRunner.terminateTree(process);
                throw new IOException("Process output reader did not finish");
            }
        }
    }

    private void extractPhaseProgress(BuildTask task, String output, String toolName) {
        List<Map<String, Object>> phases = new ArrayList<>();
        if ("maven".equals(toolName)) {
            var pattern =
                    java.util.regex.Pattern.compile("\\[INFO\\] --- ([a-zA-Z0-9._-]+):([0-9.]+):([a-zA-Z-]+)\\s.*---");
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
            summary.put("createdAt", task.createdAt.toString());
            if (task.completedAt != null) {
                summary.put("completedAt", task.completedAt.toString());
            }
            if (task.exitCode != null) {
                summary.put("exitCode", task.exitCode);
            }
            // Project directories are often Git repositories. Never persist a
            // command, exception text, or plugin/task name that may contain PII.

            Path taskFile = tasksDir.resolve(task.taskId + ".json");
            Files.writeString(
                    taskFile,
                    JsonUtils.toJson(summary),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[WARN] Async task summary could not be persisted");
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
        volatile boolean cancellationRequested;
        volatile List<Map<String, Object>> phaseProgress;
        volatile Map<String, Object> parsedResult;
        volatile W3CTraceContext inboundTrace;

        final BoundedProcessOutput output = new BoundedProcessOutput();

        BuildTask(
                String taskId,
                String toolName,
                String command,
                String projectDir,
                String buildToolHome,
                Instant createdAt) {
            this.taskId = taskId;
            this.toolName = toolName;
            this.command = command;
            this.projectDir = projectDir;
            this.buildToolHome = buildToolHome;
            this.createdAt = createdAt;
        }
    }
}
