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

import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a Maven installation directory in a portable way inside the server
 * process.
 * <p>
 * Checks, in order:
 * <ol>
 *   <li>the {@code MAVEN_HOME} environment variable (as read from the server
 *       process — for stdio launches this is inherited from the invoking
 *       shell);</li>
 *   <li>the {@code maven.home} system property;</li>
 *   <li>a {@code mvn} executable on {@code PATH}, resolving symlinks up to the
 *       installation directory.</li>
 * </ol>
 * <p>
 * This makes the documented {@code MAVEN_HOME} fallback real: previously the
 * variable was never read by the server, so callers without an explicit
 * {@code buildToolHome} always failed validation even with {@code MAVEN_HOME}
 * set in the invoking shell.
 */
public final class MavenHomeResolver {

    private MavenHomeResolver() {}

    /**
     * Resolve the Maven installation directory from the current process
     * environment.
     *
     * @return the resolved Maven home directory, or empty if none can be found
     */
    public static Optional<String> resolveMavenHome() {
        return resolveMavenHome(System.getenv("MAVEN_HOME"), System.getenv("PATH"), System.getProperty("maven.home"));
    }

    /**
     * Resolve the Maven installation directory from explicit inputs.
     *
     * @param mavenHomeEnv value of the {@code MAVEN_HOME} environment variable
     *                     (may be null)
     * @param pathEnv      value of the {@code PATH} environment variable (may be null)
     * @param mavenHomeProp value of the {@code maven.home} system property (may be null)
     * @return the resolved Maven home directory, or empty if none can be found
     */
    public static Optional<String> resolveMavenHome(String mavenHomeEnv, String pathEnv, String mavenHomeProp) {
        // 1. MAVEN_HOME environment variable
        Optional<String> fromEnv = fromDirectory(mavenHomeEnv);
        if (fromEnv.isPresent()) {
            return fromEnv;
        }

        // 2. maven.home system property
        Optional<String> fromProp = fromDirectory(mavenHomeProp);
        if (fromProp.isPresent()) {
            return fromProp;
        }

        // 3. mvn executable on PATH — resolve symlinks (.../bin/mvn -> .../apache-maven-x/bin/mvn)
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                java.nio.file.Path mvnPath = java.nio.file.Path.of(dir, "mvn");
                if (java.nio.file.Files.isExecutable(mvnPath)) {
                    try {
                        java.nio.file.Path real = mvnPath.toRealPath();
                        java.nio.file.Path home =
                                real.getParent() != null ? real.getParent().getParent() : null;
                        Optional<String> resolved = fromDirectory(home != null ? home.toString() : null);
                        if (resolved.isPresent()) {
                            return resolved;
                        }
                    } catch (Exception ignored) {
                        // fall through to next PATH entry
                    }
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<String> fromDirectory(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        try {
            Path dir = java.nio.file.Path.of(candidate);
            if (java.nio.file.Files.isDirectory(dir)) {
                return Optional.of(dir.toRealPath().toString());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Optional.empty();
    }
}
