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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared test utilities for build tools tests.
 * <p>
 * Resolves paths that work in both local development and CI environments.
 */
public final class TestUtils {

    private TestUtils() {
        // utility class
    }

    /**
     * Resolves the Maven installation directory in a portable way.
     * Checks: MAVEN_HOME env var → maven.home system property →
     * PATH lookup (mvn command) → common install paths.
     *
     * @return the resolved Maven home directory
     * @throws IllegalStateException if no Maven installation is found
     */
    public static String resolveMavenHome() {
        // 1. Environment variable
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && Files.isDirectory(Path.of(mavenHome))) {
            return mavenHome;
        }

        // 2. System property
        mavenHome = System.getProperty("maven.home");
        if (mavenHome != null && Files.isDirectory(Path.of(mavenHome))) {
            return mavenHome;
        }

        // 3. PATH lookup: resolve symlinks to find Maven installation dir
        String[] pathDirs = System.getenv("PATH") != null
                ? System.getenv("PATH").split(File.pathSeparator)
                : new String[0];
        for (String dir : pathDirs) {
            Path mvnPath = Path.of(dir, "mvn");
            if (Files.isExecutable(mvnPath)) {
                try {
                    // Resolve symlinks: .../bin/mvn → .../maven-3.9.9/bin/mvn
                    Path realPath = mvnPath.toRealPath();
                    // Go up from bin/mvn to the Maven home
                    Path resolved = realPath.getParent().getParent();
                    if (Files.isDirectory(resolved)) {
                        return resolved.toString();
                    }
                } catch (Exception ignored) {
                    // fall through to next path entry
                }
            }
        }

        // 4. Common install paths (GitHub Actions, Linux, macOS)
        String[] commonPaths = {
            "/usr/share/maven",
            "/usr/local/maven",
            "/opt/maven",
            "/opt/apache-maven-3.9.9",
            System.getProperty("user.home") + "/.sdkman/candidates/maven/current"
        };
        for (String common : commonPaths) {
            if (Files.isDirectory(Path.of(common))) {
                return common;
            }
        }

        throw new IllegalStateException(
                "Cannot find Maven installation. Set MAVEN_HOME environment variable, " +
                "add mvn to PATH, or install Maven to a common location.");
    }
}
