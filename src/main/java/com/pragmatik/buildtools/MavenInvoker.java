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

import org.apache.maven.cli.MavenCli;
import org.apache.maven.shared.invoker.*;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

public class MavenInvoker {

    static String executeCommandUsingMavenInvoker(String mavenHome, String[] commands, String currentProjectDirectory) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setInputStream(InputStream.nullInputStream());
        request.setBaseDirectory(new File(currentProjectDirectory));
        request.addArgs(Arrays.asList(commands));

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
                    result.getExecutionException().printStackTrace();
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
        StringBuilder output = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        OutputStream outputStream = new OutputStream() {
            @Override
            public void write(int b) {
                output.append((char) b);
            }
        };

        OutputStream errorStream = new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                errors.append((char) b);
            }
        };

        PrintStream outPrintStream = new PrintStream(outputStream);
        PrintStream errPrintStream = new PrintStream(errorStream);

        MavenCli mavenCli = new MavenCli();

        int exitCode = mavenCli.doMain(command, currentProjectDirectory, outPrintStream, errPrintStream);

        if (exitCode != 0) {
            finalResult = errors.toString();
            throw new RuntimeException("Maven embedder exited with code " + exitCode + ": " + finalResult);
        } else {
            finalResult = output.toString();
        }
        return finalResult;
    }

    // Allowed Maven lifecycle phases and version flags
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "clean", "compile", "test", "package", "install", "deploy", "validate",
            "--version", "-v", "-version",
            "dependency:tree"  // read-only analysis, commonly needed
    );

    // Dangerous plugin goals that can execute arbitrary code
    private static final Set<String> BLOCKED_PLUGIN_PREFIXES = Set.of(
            "exec:", "ant:", "antrun:", "sql:", "groovy:", "shell:", "help:",
            "dependency:", "resources:", "plugin:", "archetype:", "release:"
    );

    // Safe Maven flag pattern: -Dkey=value, -f file, -P profile, -q, -X, -T4, -B, -U, etc.
    // Also accepts --long-flags like --batch-mode, --non-recursive
    private static final Pattern SAFE_ARG_PATTERN =
            Pattern.compile("^-{1,2}[A-Za-z0-9][A-Za-z0-9._-]*(=[A-Za-z0-9._/:@\\-]*)?$");

    static String[] getCommands(String command) {
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
                                "Blocked plugin goal: " + token +
                                        ". Allowed commands: " + ALLOWED_COMMANDS);
                    }
                }
            }

            // Non-flag tokens must be in the allowed list
            if (!token.startsWith("-")) {
                throw new IllegalArgumentException(
                        "Command not allowed: " + token +
                                ". Allowed: " + ALLOWED_COMMANDS);
            }

            // Validate flags against safe pattern
            if (!SAFE_ARG_PATTERN.matcher(token).matches()) {
                throw new IllegalArgumentException(
                        "Invalid flag/argument: " + token);
            }
            validated.add(token);
        }

        return validated.toArray(new String[0]);
    }

    static boolean invocationResultedInError(InvocationResult result) {
        return result.getExitCode() != 0;
    }
}
