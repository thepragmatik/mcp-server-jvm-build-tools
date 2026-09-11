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

import com.pragmatik.buildtools.build.BuildTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Maven implementation of the {@link BuildTool} SPI.
 * <p>
 * Uses {@link MavenInvoker} for both embedded (version query) and
 * out-of-process (build execution) Maven operations. All security
 * validation (command allowlist, input sanitization, path canonicalization)
 * is performed by the existing MavenInvoker implementation.
 */
public class MavenBuildTool implements BuildTool {

    private static final List<String> SUPPORTED_COMMANDS =
            List.of("clean", "compile", "test", "package", "install", "deploy", "validate");

    private static final String EXECUTION_PROMPT = """
            You are an assistant for executing Maven build commands. Follow these rules:

            1. Only execute Maven lifecycle phases: clean, compile, test, package, install, deploy, validate.
            2. Allowed flags: -Dproperty=value, -f, -P, -q, -X, -T, -B, -U, --batch-mode, --non-recursive.
            3. Do not invoke plugin goals directly (e.g., exec:exec, ant:ant). These are blocked.
            4. Always verify mavenHome and projectDir paths are valid before executing.
            5. If unsure about the command, ask for clarification instead of guessing.
            """;

    @Override
    public String getName() {
        return "maven";
    }

    @Override
    public String version() {
        System.setProperty("maven.multiModuleProjectDirectory", new java.io.File(".").getAbsolutePath());
        return MavenInvoker.executeUsingMavenEmbedder(new String[] {"--version"}, ".");
    }

    @Override
    public String executeCommand(String buildToolHome, String projectDir, String command) {
        String home = requireMavenHome(buildToolHome);
        return MavenInvoker.executeCommandUsingMavenInvoker(
                home, MavenInvoker.getCommands(command), projectDir);
    }

    /**
     * Resolve the effective Maven home for a command execution.
     * <p>
     * Falls back to {@link MavenHomeResolver} ({@code MAVEN_HOME} env var,
     * {@code maven.home} system property, {@code mvn} on PATH) when no explicit
     * {@code buildToolHome} is provided, so the documented environment-variable
     * path actually works in the server process.
     *
     * @throws IllegalArgumentException with the canonical validation message when
     *                                  no Maven installation can be resolved
     */
    public static String requireMavenHome(String buildToolHome) {
        return requireMavenHome(buildToolHome, MavenHomeResolver::resolveMavenHome);
    }

    /**
     * Resolve the effective Maven home for a command execution using an explicit
     * resolver (test hook).
     */
    public static String requireMavenHome(
            String buildToolHome, java.util.function.Supplier<java.util.Optional<String>> resolver) {
        if (buildToolHome != null && !buildToolHome.isBlank()) {
            return buildToolHome;
        }
        return resolver.get().orElseThrow(() -> new IllegalArgumentException(
                "Maven requires buildToolHome. Specify a Maven installation directory."));
    }

    @Override
    public boolean isProject(Path projectDir) {
        return Files.exists(projectDir.resolve("pom.xml"));
    }

    @Override
    public List<String> getSupportedCommands() {
        return SUPPORTED_COMMANDS;
    }

    @Override
    public String getExecutionPrompt() {
        return EXECUTION_PROMPT;
    }
}
