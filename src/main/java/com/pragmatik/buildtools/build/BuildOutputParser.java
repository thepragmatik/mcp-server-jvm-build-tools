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

import com.pragmatik.buildtools.gradle.GradleOutputParser;
import com.pragmatik.buildtools.maven.MavenOutputParser;

import java.util.Map;

/**
 * Service Provider Interface for parsing build tool CLI output into
 * structured JSON results.
 * <p>
 * Each build tool (Maven, Gradle, SBT, etc.) has its own CLI output
 * format. Parsers implement this interface to extract structured data
 * — success/failure, test counts, error locations, warnings, and
 * duration — from raw console output.
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@link MavenOutputParser} — parses {@code mvn} output</li>
 *   <li>{@link GradleOutputParser} — parses {@code gradle} output</li>
 * </ul>
 */
public interface BuildOutputParser {

    /**
     * Returns the canonical name of the build tool this parser handles
     * (e.g., "maven", "gradle").
     */
    String getToolName();

    /**
     * Parse raw build output into a structured result map.
     *
     * @param rawOutput the full console output from a build tool invocation
     * @param exitCode  the process exit code (0 = success, non-zero = failure)
     * @param command   the command that was executed (for context in the result)
     * @return a structured map with keys: success, tool, command, testSummary,
     *         errors, warnings, duration, rawOutput
     */
    Map<String, Object> parse(String rawOutput, int exitCode, String command);
}
