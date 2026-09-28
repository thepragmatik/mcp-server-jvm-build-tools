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
package com.pragmatik.buildtools.maven;

import com.pragmatik.buildtools.build.BoundedProcessOutput;
import com.pragmatik.buildtools.build.SyncProcessRunner;
import com.pragmatik.buildtools.tracing.TraceContextHolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.maven.cli.MavenCli;

public class MavenInvoker {

    static String executeCommand(String mavenHome, String[] commands, String currentProjectDirectory) {
        try {
            MavenProcessExecution execution = executeWithProcessCapture(mavenHome, commands, currentProjectDirectory);
            Process process = execution.process();
            boolean finished;
            try {
                finished = process.waitFor(
                        SyncProcessRunner.resolveTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                SyncProcessRunner.terminateTree(process);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Maven execution interrupted", e);
            }
            if (!finished) {
                SyncProcessRunner.terminateTree(process);
                throw new SyncProcessRunner.ExecutionTimeoutException("Maven execution timed out");
            }
            try {
                execution.outputCollector().join(5000);
            } catch (InterruptedException e) {
                SyncProcessRunner.terminateTree(process);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Maven output collection interrupted", e);
            }
            if (execution.outputCollector().isAlive()) {
                SyncProcessRunner.terminateTree(process);
                throw new IOException("Maven output collector did not finish");
            }
            String stdout = execution.output().snapshot();
            if (process.exitValue() != 0) {
                // Maven compile and test failures commonly appear on stdout.
                String stderr = execution.errors().snapshot();
                throw new RuntimeException("Maven exited with code " + process.exitValue() + ":\n"
                        + (stderr.isEmpty() ? stdout : stderr + "\n" + stdout));
            }
            return stdout;
        } catch (IOException e) {
            throw new RuntimeException("Unable to invoke Maven command", e);
        }
    }

    static String executeUsingMavenEmbedder(String[] command, String currentProjectDirectory) {
        String finalResult;

        // Keep only bounded leading and trailing UTF-8 output from the in-process
        // version probe. PrintStream writes bytes directly to these collectors.
        BoundedProcessOutput outputStream = new BoundedProcessOutput();
        BoundedProcessOutput errorStream = new BoundedProcessOutput();

        PrintStream outPrintStream = new PrintStream(outputStream, false, StandardCharsets.UTF_8);
        PrintStream errPrintStream = new PrintStream(errorStream, false, StandardCharsets.UTF_8);

        MavenCli mavenCli = new MavenCli();

        int exitCode = mavenCli.doMain(command, currentProjectDirectory, outPrintStream, errPrintStream);

        // Flush so all buffered, encoded bytes reach the backing buffers before decode.
        outPrintStream.flush();
        errPrintStream.flush();

        String outText = outputStream.snapshot();
        String errText = errorStream.snapshot();

        if (exitCode != 0) {
            finalResult = errText;
            throw new RuntimeException("Maven embedder exited with code " + exitCode + ": " + finalResult);
        } else {
            finalResult = outText;
        }
        return finalResult;
    }

    // Allowed Maven lifecycle phases and version flags
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "clean",
            "compile",
            "test",
            "package",
            "install",
            "validate",
            "--version",
            "-v",
            "-version",
            "dependency:tree" // read-only analysis, commonly needed
            );

    // Dangerous plugin goals that can execute arbitrary code
    private static final Set<String> BLOCKED_PLUGIN_PREFIXES = Set.of(
            "exec:",
            "ant:",
            "antrun:",
            "sql:",
            "groovy:",
            "shell:",
            "help:",
            "dependency:",
            "resources:",
            "plugin:",
            "archetype:",
            "release:");

    // Positive option list: Maven's other flags can select files and projects
    // outside the validated root, including POMs, settings, and toolchains.
    private static final Set<String> ALLOWED_FLAGS = Set.of(
            "-q",
            "--quiet",
            "-X",
            "--debug",
            "-B",
            "--batch-mode",
            "-U",
            "--update-snapshots",
            "-N",
            "--non-recursive",
            "-e",
            "--errors",
            "-o",
            "--offline",
            "-ntp",
            "--no-transfer-progress");
    private static final Pattern PROPERTY_FLAG =
            Pattern.compile("^-D([A-Za-z0-9][A-Za-z0-9._-]*)(?:=[A-Za-z0-9._/:@\\-]*)?$");
    private static final Pattern PROFILE_FLAG = Pattern.compile("^-P[A-Za-z0-9._,-]+$");
    private static final Pattern THREAD_FLAG = Pattern.compile("^-T[1-8]$");
    private static final Set<String> BLOCKED_PROPERTIES = Set.of(
            "maven.ext.class.path", "maven.repo.local", "maven.multimoduleprojectdirectory", "maven.home", "user.home");

    public static String[] getCommands(String command) {
        Objects.requireNonNull(command, "command must not be null");

        var cmd = command.trim();
        if (cmd.startsWith("mvn ")) {
            cmd = cmd.substring("mvn ".length()).trim();
        } else if (cmd.equals("mvn")) {
            cmd = "";
        }
        if (cmd.isEmpty()) {
            return new String[0];
        }

        String[] tokens = cmd.split("\\s+");
        List<String> validated = new ArrayList<>();

        for (String token : tokens) {
            // Check allowed commands first (includes --version, -v, -version)
            if (ALLOWED_COMMANDS.contains(token)) {
                validated.add(token);
                continue;
            }

            // Block dangerous plugin goals (contains ':' and is not a flag)
            if (token.contains(":")) {
                for (String prefix : BLOCKED_PLUGIN_PREFIXES) {
                    if (token.toLowerCase().startsWith(prefix)) {
                        throw new IllegalArgumentException(
                                "Blocked plugin goal: " + token + ". Allowed commands: " + ALLOWED_COMMANDS);
                    }
                }
            }

            // Non-flag tokens must be in the allowed list
            if (!token.startsWith("-")) {
                throw new IllegalArgumentException("Command not allowed: " + token + ". Allowed: " + ALLOWED_COMMANDS);
            }

            var property = PROPERTY_FLAG.matcher(token);
            boolean allowedProperty = property.matches()
                    && !BLOCKED_PROPERTIES.contains(property.group(1).toLowerCase(java.util.Locale.ROOT));
            if (!ALLOWED_FLAGS.contains(token)
                    && !allowedProperty
                    && !PROFILE_FLAG.matcher(token).matches()
                    && !THREAD_FLAG.matcher(token).matches()) {
                throw new IllegalArgumentException("Invalid flag/argument");
            }
            validated.add(token);
        }

        return validated.toArray(new String[0]);
    }

    /**
     * A cancellable Maven execution that exposes the underlying {@link Process}
     * so the async build service can destroy it on task cancellation.
     */
    public record MavenProcessExecution(
            Process process, Thread outputCollector, BoundedProcessOutput output, BoundedProcessOutput errors) {}

    /**
     * Execute a Maven command using {@link ProcessBuilder} so the caller can
     * capture the {@link Process} handle for cancellation.
     * <p>
     * Launches {@code mvn} (or {@code mvnw}) from the given Maven home directory
     * with the validated command tokens. Collected stdout/stderr are available
     * via the returned {@link MavenProcessExecution} record.
     * <p>
     * <b>Security:</b> Callers must validate commands via
     * {@link #getCommands(String)} before passing them here.
     *
     * @param mavenHome  path to the Maven installation (containing bin/mvn or mvnw)
     * @param commands   pre-validated command tokens
     * @param projectDir the project directory to run in
     * @return a handle to the running process and output collectors
     * @throws IOException if the process cannot be started
     */
    public static MavenProcessExecution executeWithProcessCapture(
            String mavenHome, String[] commands, String projectDir) throws IOException {
        Path homePath = Path.of(mavenHome);
        Path mvnw = homePath.resolve("mvnw");
        Path mvnBin = homePath.resolve("bin/mvn");
        String executable;
        if (Files.isExecutable(mvnw)) {
            executable = mvnw.toString();
        } else if (Files.isExecutable(mvnBin)) {
            executable = mvnBin.toString();
        } else {
            executable = "mvn";
        }

        List<String> cmdList = new ArrayList<>();
        cmdList.add(executable);
        cmdList.addAll(Arrays.asList(commands));

        ProcessBuilder pb = new ProcessBuilder(cmdList);
        pb.directory(new File(projectDir));
        // Propagate the active W3C trace context (SEP-414) to the Maven subprocess.
        TraceContextHolder.applyToEnvironment(pb.environment());
        Process process = pb.start();

        BoundedProcessOutput output = new BoundedProcessOutput();
        BoundedProcessOutput errors = new BoundedProcessOutput();

        // Drain stdout and stderr concurrently to avoid the pipe-buffer deadlock that
        // occurs when one stream is read to EOF before the other is drained. The
        // single collector thread coordinates two dedicated reader threads so callers
        // can still join one handle to know when all output has been captured.
        Thread collector = new Thread(
                () -> {
                    Thread outThread = SyncProcessRunner.drain(process.getInputStream(), output, "maven-stdout");
                    Thread errThread = SyncProcessRunner.drain(process.getErrorStream(), errors, "maven-stderr");
                    try {
                        outThread.join();
                        errThread.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                "maven-output-collector");
        collector.setDaemon(true);
        collector.start();

        return new MavenProcessExecution(process, collector, output, errors);
    }
}
