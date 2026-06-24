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

import java.nio.file.Path;
import java.util.List;

/**
 * Service Provider Interface for build tools on the JVM.
 * <p>
 * Implementations encapsulate build-tool-specific invocation logic
 * (embedder, CLI, Tooling API) behind a uniform contract. New build
 * tools (SBT, Bazel, Ant, etc.) can be added by implementing this
 * interface and registering with {@link BuildToolProvider}.
 */
public interface BuildTool {

    /**
     * Returns the canonical name of this build tool (e.g., "maven", "gradle").
     */
    String getName();

    /**
     * Returns the installed version of this build tool.
     * For Maven this uses the embedder; for Gradle/SBT it shells out.
     */
    String version();

    /**
     * Execute a build command in the specified project directory.
     *
     * @param buildToolHome path to the build tool installation directory
     * @param projectDir    path to the project directory containing build files
     * @param command       the build command string (e.g., "clean compile")
     * @return standard output from the build tool execution
     */
    String executeCommand(String buildToolHome, String projectDir, String command);

    /**
     * Detect whether the given directory contains a project of this build tool.
     * Maven looks for pom.xml; Gradle looks for build.gradle or settings.gradle.
     *
     * @param projectDir the directory to inspect
     * @return true if this build tool's project markers are found
     */
    boolean isProject(Path projectDir);

    /**
     * Returns the list of supported lifecycle commands/tasks for this build tool.
     * Used for validation and LLM prompt construction.
     */
    List<String> getSupportedCommands();

    /**
     * Returns an LLM execution prompt tailored to this build tool's command syntax,
     * validation rules, and security boundaries.
     */
    String getExecutionPrompt();
}
