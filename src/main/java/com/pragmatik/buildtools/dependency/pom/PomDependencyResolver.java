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
package com.pragmatik.buildtools.dependency.pom;

import com.pragmatik.buildtools.dependency.pom.PomModel.AnalysisResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin facade for POM dependency resolution.
 * <p>
 * Delegates to {@link PomAnalyzer} and adds Maven/Gradle project detection
 * so that callers (specifically {@code DependencyService}) get a clear error
 * message when the tool is invoked on a non-Maven project.
 *
 * @see PomAnalyzer
 * @see PomModel
 */
public class PomDependencyResolver {

    private final PomAnalyzer analyzer;

    public PomDependencyResolver() {
        this.analyzer = new PomAnalyzer();
    }

    public PomDependencyResolver(Path localRepoPath) {
        this.analyzer = new PomAnalyzer(localRepoPath);
    }

    /**
     * Resolve dependencies from a project directory.
     *
     * @param projectDir        path to the project directory
     * @param resolveTransitive reserved for future use
     * @return the analysis result
     * @throws IOException              if pom.xml cannot be read
     * @throws IllegalArgumentException if the project is not a Maven POM project
     */
    public AnalysisResult resolve(Path projectDir, boolean resolveTransitive) throws IOException {
        // Reject non-Maven projects with a clear error
        if (Files.exists(projectDir.resolve("build.gradle")) || Files.exists(projectDir.resolve("build.gradle.kts"))) {
            throw new IllegalArgumentException(
                    "This tool requires a Maven POM project. Detected Gradle build files instead.");
        }
        if (Files.exists(projectDir.resolve("build.sbt"))) {
            throw new IllegalArgumentException(
                    "This tool requires a Maven POM project. Detected SBT build file instead.");
        }
        if (!Files.exists(projectDir.resolve("pom.xml"))) {
            throw new IllegalArgumentException(
                    "No pom.xml found in " + projectDir + ". This tool requires a Maven POM project.");
        }

        return analyzer.analyze(projectDir, resolveTransitive);
    }
}
