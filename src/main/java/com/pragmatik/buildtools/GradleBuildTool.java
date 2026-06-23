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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Gradle implementation of the {@link BuildTool} SPI.
 * <p>
 * Uses CLI-based invocation via {@link ProcessBuilder}. This avoids a hard
 * dependency on the Gradle Tooling API (which is not published to Maven Central).
 * Commands are executed with {@code --no-daemon --console=plain} for clean,
 * machine-readable output suitable for MCP server contexts.
 * <p>
 * <b>Security:</b> All Gradle commands are validated against an allowlist of
 * safe tasks, a blocklist of dangerous flags, and a safe-argument pattern —
 * mirroring the same security model as {@link MavenInvoker}.
 * <p>
 * <b>Upgrade path:</b> When the Gradle Tooling API becomes available as a dependency,
 * switch {@link #executeCommand} to use {@code GradleConnector.newConnector()}
 * for in-process execution with richer error reporting. The CLI fallback in
 * {@link #resolveGradleExecutable} can be retained as a backup.
 */
public class GradleBuildTool implements BuildTool {

    private static final Set<String> ALLOWED_TASKS = Set.of(
            "clean", "build", "test", "compileJava", "compileTestJava",
            "jar", "assemble", "check", "publishToMavenLocal",
            "dependencies", "projects", "tasks"
    );

    private static final List<String> SUPPORTED_COMMANDS = List.copyOf(ALLOWED_TASKS);

    private static final List<String> MARKER_FILES = List.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"
    );

    // Dangerous Gradle flags that enable code execution or arbitrary file access
    private static final Set<String> BLOCKED_GRADLE_FLAGS = Set.of(
            "--init-script", "-I",
            "--build-file", "-b",
            "--project-dir", "-p",
            "--include-build",
            "--system-prop", "-D"
    );

    // Safe Gradle flag pattern: --flag or -X (single letters for standard options)
    private static final Pattern SAFE_ARG_PATTERN =
            Pattern.compile("^-{1,2}[A-Za-z0-9][A-Za-z0-9._-]*(=[A-Za-z0-9._/:@\\\\-]*)?$");

    private static final int MAX_COMMAND_LENGTH = 500;

    private static final String EXECUTION_PROMPT = """
            You are an assistant for executing Gradle build commands. Follow these rules:

            1. Only execute Gradle lifecycle tasks: clean, build, test, compileJava, compileTestJava,
               jar, assemble, check, publishToMavenLocal, dependencies, projects, tasks.
            2. Supported flags: --no-daemon (always added), --console=plain (always added),
               -x, --exclude-task, --parallel, --configure-on-demand, --build-cache.
            3. Do not execute arbitrary system commands or shell scripts.
            4. Always verify the Gradle home path or project directory before executing.
            5. If the project has a gradlew wrapper, prefer it over a system Gradle installation.
            """;

    @Override
    public String getName() {
        return "gradle";
    }

    @Override
    public String version() {
        try {
            String[] cmd = {resolveGradleExecutable(null, null), "--version", "--no-daemon"};
            Process process = new ProcessBuilder(cmd).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                StringBuilder errors = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errors.append(line).append(System.lineSeparator());
                    }
                }
                throw new RuntimeException("Gradle --version failed with exit code " + exitCode + ": " + errors);
            }
            return output.toString().trim();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Unable to determine Gradle version: " + e.getMessage(), e);
        }
    }

    @Override
    public String executeCommand(String buildToolHome, String projectDir, String command) {
        String executable = resolveGradleExecutable(buildToolHome, projectDir);
        String[] tokens = parseCommandTokens(command);

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.addAll(Arrays.asList(tokens));
        cmdList.add("--no-daemon");
        cmdList.add("--console=plain");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.directory(new File(projectDir));
            Process process = pb.start();

            // Drain stdout and stderr concurrently with a bounded execution timeout
            // to avoid the pipe-buffer deadlock that occurs when one stream is read
            // to EOF before the other is drained.
            SyncProcessRunner.Result result = SyncProcessRunner.run(process, "gradle");
            if (result.exitCode() != 0) {
                throw new RuntimeException(
                        "Gradle exited with code " + result.exitCode() + ": " + result.stderr());
            }
            return result.stdout();
        } catch (IOException e) {
            throw new RuntimeException("Unable to invoke Gradle command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gradle command interrupted: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isProject(Path projectDir) {
        for (String marker : MARKER_FILES) {
            if (Files.exists(projectDir.resolve(marker))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> getSupportedCommands() {
        return SUPPORTED_COMMANDS;
    }

    @Override
    public String getExecutionPrompt() {
        return EXECUTION_PROMPT;
    }

    /**
     * Resolves the Gradle executable from multiple sources, in order of preference:
     * <ol>
     *   <li>{@code buildToolHome}/bin/gradle (if buildToolHome is provided)</li>
     *   <li>{@code buildToolHome}/gradlew (if buildToolHome points to a project)</li>
     *   <li>{@code projectDir}/gradlew (Gradle wrapper)</li>
     *   <li>{@code "gradle"} on system PATH (fallback)</li>
     * </ol>
     * <p>
     * <b>Security:</b> Rejects bare executables (not ending in /gradle or /gradlew)
     * to prevent arbitrary binary execution. Only accepts directory paths or
     * well-known Gradle executable names.
     */
    static String resolveGradleExecutable(String buildToolHome, String projectDir) {
        if (buildToolHome != null && !buildToolHome.isEmpty()) {
            Path home = Path.of(buildToolHome);
            Path gradleBin = home.resolve("bin/gradle");
            if (Files.isExecutable(gradleBin)) return gradleBin.toString();
            Path gradlew = home.resolve("gradlew");
            if (Files.isExecutable(gradlew)) return gradlew.toString();
            // Only accept the path if it's a Gradle binary or wrapper, otherwise fall through
            if (Files.isExecutable(home)) {
                String name = home.getFileName().toString();
                if (name.equals("gradle") || name.equals("gradlew")) {
                    return home.toString();
                }
            }
        }
        if (projectDir != null && !projectDir.isEmpty()) {
            Path gradlew = Path.of(projectDir).resolve("gradlew");
            if (Files.isExecutable(gradlew)) return gradlew.toString();
        }
        return "gradle";
    }

    /**
     * Parses a command string into validated Gradle task tokens.
     * Strips leading "gradle " or "gradlew " prefix, then splits on whitespace.
     * <p>
     * <b>Security:</b> Every token is validated against: (1) the ALLOWED_TASKS set,
     * (2) the BLOCKED_GRADLE_FLAGS blocklist, and (3) the SAFE_ARG_PATTERN regex.
     * This mirrors the same security model as {@link MavenInvoker#getCommands(String)}.
     */
    static String[] parseCommandTokens(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Gradle command cannot be null or empty");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException(
                    "Command too long (max " + MAX_COMMAND_LENGTH + " characters).");
        }
        var cmd = command.trim();
        if (cmd.startsWith("gradle ")) {
            cmd = cmd.substring("gradle ".length()).trim();
        } else if (cmd.startsWith("gradlew ")) {
            cmd = cmd.substring("gradlew ".length()).trim();
        } else if (cmd.equals("gradle") || cmd.equals("gradlew")) {
            cmd = "";
        }
        if (cmd.isEmpty()) {
            return new String[0];
        }

        String[] tokens = cmd.split("\\s+");
        List<String> validated = new ArrayList<>();

        for (String token : tokens) {
            // Non-flag tokens must be in allowed list.
            // For colon-separated Gradle project paths (e.g., ":app:build"), extract
            // the task name (last colon-separated segment) for validation.
            if (!token.startsWith("-")) {
                String taskName = token.contains(":")
                        ? token.substring(token.lastIndexOf(':') + 1)
                        : token;
                if (!ALLOWED_TASKS.contains(taskName)) {
                    throw new IllegalArgumentException(
                            "Gradle task not allowed: " + taskName +
                                    ". Allowed: " + ALLOWED_TASKS);
                }
                validated.add(token);
                continue;
            }

            // Block dangerous flags
            for (String blocked : BLOCKED_GRADLE_FLAGS) {
                if (token.startsWith(blocked)) {
                    throw new IllegalArgumentException(
                            "Blocked Gradle flag: " + token);
                }
            }

            // Validate safe flags against pattern
            if (!SAFE_ARG_PATTERN.matcher(token).matches()) {
                throw new IllegalArgumentException(
                        "Invalid flag/argument: " + token);
            }
            validated.add(token);
        }

        return validated.toArray(new String[0]);
    }
}
