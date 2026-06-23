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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SBT (Scala Build Tool) implementation of the {@link BuildTool} SPI.
 * <p>
 * Uses CLI-based invocation via {@link ProcessBuilder}, similar to
 * {@link GradleBuildTool}. SBT does not publish a stable embedder API
 * to Maven Central, so CLI invocation is the most portable approach.
 * <p>
 * <b>Security:</b> All SBT commands are validated against an allowlist of
 * safe tasks and a safe-argument pattern — mirroring the same security
 * model as {@link MavenInvoker} and {@link GradleBuildTool}.
 * <p>
 * <b>First-mover position:</b> No SBT MCP server exists on GitHub as of 2025.
 * This is the first MCP server to offer SBT build execution alongside
 * Maven and Gradle through a unified SPI.
 */
public class SbtBuildTool implements BuildTool {

    private static final Set<String> ALLOWED_TASKS = Set.of(
            "compile",
            "test",
            "run",
            "package",
            "clean",
            "assembly",
            "publishLocal",
            "publish",
            "update",
            "doc",
            "console");

    private static final List<String> SUPPORTED_COMMANDS = List.copyOf(ALLOWED_TASKS);

    private static final String MARKER_FILE = "build.sbt";

    private static final Set<String> BLOCKED_SBT_FLAGS =
            Set.of("-D", "-J", "-sbt-dir", "-sbt-boot", "-sbt-launch-dir", "-ivy", "-maven-launcher");

    // Safe SBT flag pattern: --flag or -X (single letters for standard options)
    private static final Pattern SAFE_ARG_PATTERN =
            Pattern.compile("^-{1,2}[A-Za-z0-9][A-Za-z0-9._-]*(=[A-Za-z0-9._/:@\\\\-]*)?$");

    private static final int MAX_COMMAND_LENGTH = 500;

    private static final String EXECUTION_PROMPT =
            """
            You are an assistant for executing SBT build commands. Follow these rules:

            1. Only execute SBT lifecycle tasks: compile, test, run, package, clean, assembly,
               publishLocal, publish, update, doc, console.
            2. Supported flags: --no-colors (always added for machine-readable output).
            3. Use semicolons to chain multiple tasks (e.g., "clean;compile;test").
            4. Do not execute arbitrary system commands or shell scripts.
            5. Always verify the project directory before executing.
            6. If the project has an sbt wrapper script, it will be auto-detected.
            """;

    @Override
    public String getName() {
        return "sbt";
    }

    @Override
    public String version() {
        try {
            String executable = resolveSbtExecutable(null, null);
            Process process = new ProcessBuilder(executable, "--no-colors", "--version").start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                StringBuilder errors = new StringBuilder();
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errors.append(line).append(System.lineSeparator());
                    }
                }
                throw new RuntimeException("sbt --version failed with exit code " + exitCode + ": " + errors);
            }
            return output.toString().trim();
        } catch (IOException e) {
            throw new RuntimeException("Unable to determine SBT version: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SBT version check interrupted", e);
        }
    }

    @Override
    public String executeCommand(String buildToolHome, String projectDir, String command) {
        String executable = resolveSbtExecutable(buildToolHome, projectDir);
        String[] tokens = parseCommandTokens(command);

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.add("--no-colors");
        cmdList.addAll(Arrays.asList(tokens));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.directory(new File(projectDir));
            Process process = pb.start();

            // Drain stdout and stderr concurrently with a bounded execution timeout
            // to avoid the pipe-buffer deadlock that occurs when one stream is read
            // to EOF before the other is drained.
            SyncProcessRunner.Result result = SyncProcessRunner.run(process, "sbt");
            if (result.exitCode() != 0) {
                throw new RuntimeException("sbt exited with code " + result.exitCode() + ": " + result.stderr());
            }
            return result.stdout();
        } catch (IOException e) {
            throw new RuntimeException("Unable to invoke sbt command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("sbt command interrupted: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isProject(Path projectDir) {
        return Files.exists(projectDir.resolve(MARKER_FILE));
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
     * Resolves the SBT executable from multiple sources, in order of preference:
     * <ol>
     *   <li>{@code buildToolHome}/bin/sbt (if buildToolHome is provided)</li>
     *   <li>{@code buildToolHome}/sbt (wrapper or direct executable)</li>
     *   <li>{@code projectDir}/sbt (SBT wrapper script)</li>
     *   <li>{@code "sbt"} on system PATH (fallback)</li>
     * </ol>
     * <p>
     * <b>Security:</b> Only accepts well-known SBT executable names (sbt or sbt.bat)
     * and directory-based resolution to prevent arbitrary binary execution.
     */
    static String resolveSbtExecutable(String buildToolHome, String projectDir) {
        if (buildToolHome != null && !buildToolHome.isEmpty()) {
            Path home = Path.of(buildToolHome);
            Path sbtBin = home.resolve("bin/sbt");
            if (Files.isExecutable(sbtBin)) return sbtBin.toString();
            Path sbtWrapper = home.resolve("sbt");
            if (Files.isExecutable(sbtWrapper)) return sbtWrapper.toString();
            // Only accept the path if it's a well-known sbt executable
            if (Files.isExecutable(home)) {
                String name = home.getFileName().toString();
                if (name.equals("sbt") || name.equals("sbt.bat")) {
                    return home.toString();
                }
            }
        }
        if (projectDir != null && !projectDir.isEmpty()) {
            Path sbtWrapper = Path.of(projectDir).resolve("sbt");
            if (Files.isExecutable(sbtWrapper)) return sbtWrapper.toString();
        }
        return "sbt";
    }

    /**
     * Parses a command string into validated SBT task tokens.
     * Strips leading "sbt " prefix, then splits on whitespace or semicolons
     * (SBT's task-chaining delimiter).
     * <p>
     * <b>Security:</b> Every token is validated against: (1) the ALLOWED_TASKS set,
     * (2) the BLOCKED_SBT_FLAGS blocklist, and (3) the SAFE_ARG_PATTERN regex.
     */
    static String[] parseCommandTokens(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("sbt command cannot be null or empty");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("Command too long (max " + MAX_COMMAND_LENGTH + " characters).");
        }
        var cmd = command.trim();
        if (cmd.startsWith("sbt ")) {
            cmd = cmd.substring("sbt ".length()).trim();
        } else if (cmd.equals("sbt")) {
            cmd = "";
        }
        if (cmd.isEmpty()) {
            return new String[0];
        }

        // Split on whitespace and semicolons (SBT's task-chaining delimiter)
        String[] tokens = cmd.split("[;\\s]+");
        List<String> validated = new ArrayList<>();

        for (String token : tokens) {
            if (token.isEmpty()) continue;

            // Non-flag tokens must be in allowed list
            if (!token.startsWith("-")) {
                if (!ALLOWED_TASKS.contains(token)) {
                    throw new IllegalArgumentException(
                            "sbt task not allowed: " + token + ". Allowed: " + ALLOWED_TASKS);
                }
                validated.add(token);
                continue;
            }

            // Block dangerous flags
            for (String blocked : BLOCKED_SBT_FLAGS) {
                if (token.startsWith(blocked)) {
                    throw new IllegalArgumentException("Blocked sbt flag: " + token);
                }
            }

            // Validate safe flags against pattern
            if (!SAFE_ARG_PATTERN.matcher(token).matches()) {
                throw new IllegalArgumentException("Invalid flag/argument: " + token);
            }
            validated.add(token);
        }

        return validated.toArray(new String[0]);
    }
}
