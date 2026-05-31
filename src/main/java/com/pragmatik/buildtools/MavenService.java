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

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Service
public class MavenService {

    private static final int MAX_COMMAND_LENGTH = 500;
    private static final Pattern COMMAND_PATTERN =
            Pattern.compile("^(mvn\\s+)?[a-zA-Z0-9\\s._=/:@\\-]+$");

    @Tool(name = "get_maven_version", description = "Gets the version for Apache Maven")
    public String version() {
        System.setProperty("maven.multiModuleProjectDirectory", new java.io.File(".").getAbsolutePath());
        return MavenInvoker.executeUsingMavenEmbedder(new String[]{"--version"}, ".");
    }

    @Tool(name = "execute_maven_command",
          description = "Execute a Maven build command in a specified project directory. " +
                        "Supports: clean, compile, test, package, install, deploy, validate.")
    public String executeCommand(
            @ToolParam(required = true, description = "Path to the Maven installation directory (e.g., /usr/share/maven)")
            String mavenHome,
            @ToolParam(required = true, description = "Path to the Maven project directory containing pom.xml")
            String projectDir,
            @ToolParam(required = true, description = "Maven command to execute (e.g., 'clean compile', 'test', 'package'). Can optionally include 'mvn' prefix.")
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
        if (mavenHome != null) {
            Path path = Path.of(mavenHome);
            try {
                path = path.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Cannot resolve maven home path: " + mavenHome, e);
            }
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("Invalid maven home directory: " + mavenHome);
            }
            System.setProperty("maven.home", path.toString());
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

        finalResult = MavenInvoker.executeCommandUsingMavenInvoker(
                mavenHome, MavenInvoker.getCommands(command), currentProjectDirectory);
        return finalResult;
    }

}
