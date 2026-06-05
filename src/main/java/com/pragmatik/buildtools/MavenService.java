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

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Legacy Maven-only service. Replaced by {@link BuildToolsService} which provides
 * equivalent functionality via {@code get_build_tool_version("maven")} and
 * {@code execute_build_command("maven", ...)} along with multi-build-tool support.
 * <p>
 * This class retains {@code @Service} for test compatibility but
 * {@code @Tool} annotations have been removed — tools are no longer exposed
 * to MCP clients. Use {@link BuildToolsService} instead.
 *
 * @deprecated Use {@link BuildToolsService} instead. This class is retained for
 *             reference and will be removed in a future release.
 */
@Deprecated
@Service
public class MavenService {

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final Pattern COMMAND_PATTERN =
            Pattern.compile("^(mvn\\s+)?[a-zA-Z0-9\\s._=/:@\\-]+$");

    public String version() {
        System.setProperty("maven.multiModuleProjectDirectory", new java.io.File(".").getAbsolutePath());
        return MavenInvoker.executeUsingMavenEmbedder(new String[]{"--version"}, ".");
    }

    public String executeCommand(
            String mavenHome,
            String projectDir,
            String command) {
        String finalResult;

        // Validate command length and character content
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("Command cannot be null or empty.");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException(
                    "Command too long (max " + MAX_COMMAND_LENGTH + " characters).");
        }
        if (!COMMAND_PATTERN.matcher(command).matches()) {
            throw new IllegalArgumentException(
                    "Command contains disallowed characters.");
        }

        // Validate and canonicalize mavenHome
        Path mavenHomePath;
        if (mavenHome != null) {
            Path path = Path.of(mavenHome);
            try {
                mavenHomePath = path.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Cannot resolve maven home path: " + mavenHome, e);
            }
            if (!Files.isDirectory(mavenHomePath)) {
                throw new IllegalArgumentException("Invalid maven home directory: " + mavenHome);
            }
            System.setProperty("maven.home", mavenHomePath.toString());
        } else {
            throw new IllegalArgumentException("Maven home cannot be null.");
        }

        // Validate and canonicalize projectDir
        String currentProjectDirectory;
        if (projectDir != null) {
            Path path = Path.of(projectDir);
            try {
                path = path.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Cannot resolve project directory: " + projectDir, e);
            }
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException(
                        "The specified project directory '%s' does not exist.".formatted(projectDir));
            }
            // Ensure it's a Maven project
            if (!Files.exists(path.resolve("pom.xml"))) {
                throw new IllegalArgumentException(
                        "Not a Maven project directory (no pom.xml): " + path);
            }
            currentProjectDirectory = path.toString();
            System.setProperty("maven.multiModuleProjectDirectory", currentProjectDirectory);
        } else {
            throw new IllegalArgumentException("Maven project directory cannot be null.");
        }

        // FIX: pass canonicalized mavenHomePath.toString() instead of raw mavenHome
        finalResult = MavenInvoker.executeCommandUsingMavenInvoker(
                mavenHomePath.toString(), MavenInvoker.getCommands(command), currentProjectDirectory);
        return finalResult;
    }

}
