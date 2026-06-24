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

import com.pragmatik.buildtools.build.SyncProcessRunner;
import com.pragmatik.buildtools.tracing.TraceContextHolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.shared.invoker.*;

public class MavenInvoker {

    static String executeCommandUsingMavenInvoker(String mavenHome, String[] commands, String currentProjectDirectory) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setInputStream(InputStream.nullInputStream());
        request.setBaseDirectory(new File(currentProjectDirectory));
        request.addArgs(Arrays.asList(commands));

        // Propagate the active W3C trace context (SEP-414) to the Maven subprocess
        // through the SAME mechanism every other build path uses
        // (TraceContextHolder.applyToEnvironment), so TRACEPARENT is set and stale
        // TRACESTATE/BAGGAGE are cleared symmetrically. The maven-invoker API can
        // only ADD shell variables, never remove them, so we materialise the full
        // subprocess environment from the server's own environment, apply the trace
        // context to that map, and pass it verbatim with inheritance disabled.
        TraceContextHolder.currentSpan().ifPresent(span -> {
            Map<String, String> environment = new HashMap<>(System.getenv());
            TraceContextHolder.applyToEnvironment(environment);
            request.setShellEnvironmentInherited(false);
            environment.forEach(request::addShellEnvironment);
        });

        Invoker invoker = new DefaultInvoker();
        invoker.setWorkingDirectory(new File(currentProjectDirectory));
        invoker.setMavenHome(new File(mavenHome));

        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        request.setOutputHandler(s -> output.append(s).append(System.lineSeparator()));
        request.setErrorHandler(s -> errors.append(s).append(System.lineSeparator()));

        String finalResult;
        try {
            InvocationResult result = invoker.execute(request);
            if (invocationResultedInError(result)) {
                if (result.getExecutionException() != null) {
                    System.err.println("[ERROR] Maven execution failed: "
                            + result.getExecutionException().getMessage());
                }
                finalResult = errors.toString();
                throw new RuntimeException(finalResult);
            } else {
                finalResult = output.toString();
            }
        } catch (MavenInvocationException e) {
            finalResult = "Unable to invoke Maven command: " + e.getMessage();
            throw new RuntimeException(finalResult);
        }

        return finalResult;
    }

    static String executeUsingMavenEmbedder(String[] command, String currentProjectDirectory) {
        String finalResult;

        // Capture the embedder's stdout/stderr as raw bytes so the UTF-8 encoding
        // applied by the PrintStreams below is decoded back symmetrically. The
        // previous sink appended one Java char per byte, which reinterpreted each
        // byte as Latin-1 and mojibake'd any multi-byte UTF-8 output. Buffering the
        // bytes and decoding once via toString(UTF_8) keeps the round-trip lossless.
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorStream = new ByteArrayOutputStream();

        PrintStream outPrintStream = new PrintStream(outputStream, false, StandardCharsets.UTF_8);
        PrintStream errPrintStream = new PrintStream(errorStream, false, StandardCharsets.UTF_8);

        MavenCli mavenCli = new MavenCli();

        int exitCode = mavenCli.doMain(command, currentProjectDirectory, outPrintStream, errPrintStream);

        // Flush so all buffered, encoded bytes reach the backing buffers before decode.
        outPrintStream.flush();
        errPrintStream.flush();

        String outText = outputStream.toString(StandardCharsets.UTF_8);
        String errText = errorStream.toString(StandardCharsets.UTF_8);

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
            "deploy",
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

    // Safe Maven flag pattern: -Dkey=value, -f file, -P profile, -q, -X, -T4, -B, -U, etc.
    // Also accepts --long-flags like --batch-mode, --non-recursive
    private static final Pattern SAFE_ARG_PATTERN =
            Pattern.compile("^-{1,2}[A-Za-z0-9][A-Za-z0-9._-]*(=[A-Za-z0-9._/:@\\-]*)?$");

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

            // Validate flags against safe pattern
            if (!SAFE_ARG_PATTERN.matcher(token).matches()) {
                throw new IllegalArgumentException("Invalid flag/argument: " + token);
            }

            // -D system properties are passed through verbatim: the server trusts the
            // client's choices entirely (no key allowlist/blocklist). Shell
            // metacharacters are still rejected by SAFE_ARG_PATTERN above.
            validated.add(token);
        }

        return validated.toArray(new String[0]);
    }

    static boolean invocationResultedInError(InvocationResult result) {
        return result.getExitCode() != 0;
    }

    /**
     * A cancellable Maven execution that exposes the underlying {@link Process}
     * so the async build service can destroy it on task cancellation.
     */
    public record MavenProcessExecution(
            Process process, Thread outputCollector, StringBuilder output, StringBuilder errors) {}

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
    public static MavenProcessExecution executeWithProcessCapture(String mavenHome, String[] commands, String projectDir)
            throws IOException {
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

        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

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
        collector.start();

        return new MavenProcessExecution(process, collector, output, errors);
    }
}
