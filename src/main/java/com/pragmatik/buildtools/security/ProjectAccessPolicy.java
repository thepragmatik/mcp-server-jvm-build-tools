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
package com.pragmatik.buildtools.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Restricts project file access to explicitly configured directory trees. */
@Component
public final class ProjectAccessPolicy {
    private static final List<String> BUILD_FILES = List.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "build.sbt",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "project/build.properties");
    private final List<Path> roots;

    public ProjectAccessPolicy(@Value("${buildtools.projects.allowed-roots:}") String configuredRoots) {
        if (configuredRoots == null || configuredRoots.isBlank()) {
            roots = List.of();
            return;
        }
        roots = Arrays.stream(configuredRoots.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(ProjectAccessPolicy::canonicalRoot)
                .distinct()
                .toList();
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("At least one project root is required");
        }
    }

    public List<Path> roots() {
        return roots;
    }

    public Path requireAllowed(String projectDir) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "Project access is disabled. Configure buildtools.projects.allowed-roots.");
        }
        if (projectDir == null || projectDir.isBlank()) {
            throw new IllegalArgumentException("Project directory is required");
        }
        try {
            Path requested = Path.of(projectDir);
            Path project =
                    (requested.isAbsolute() ? requested : roots.getFirst().resolve(requested)).toRealPath();
            if (roots.stream().noneMatch(project::startsWith)) {
                throw new IllegalArgumentException("Project directory is outside configured project roots");
            }
            checkBuildMarkers(project);
            return project;
        } catch (IOException | java.nio.file.InvalidPathException | SecurityException e) {
            throw new IllegalArgumentException("Project directory cannot be resolved");
        }
    }

    private void checkBuildMarkers(Path project) {
        AnchoredProjectFileReader.ProjectDirectory opened;
        try {
            opened = AnchoredProjectFileReader.open(project);
        } catch (AnchoredProjectFileReader.UnsupportedProviderException unsupported) {
            checkBuildMarkersCompatibly(project);
            return;
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("Project directory cannot be safely inspected");
        }
        try (AnchoredProjectFileReader.ProjectDirectory directory = opened) {
            for (String name : BUILD_FILES) {
                if (!name.contains("/")) directory.requireSafeMarker(name);
            }
            directory.requireSafeNestedMarker("project", "build.properties");
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("Project directory cannot be safely inspected");
        }
    }

    /** Compatibility path for providers without SecureDirectoryStream; not race-free. */
    void checkBuildMarkersCompatibly(Path project) {
        try {
            for (String name : BUILD_FILES) {
                Path file = project.resolve(name);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                        && roots.stream().noneMatch(file.toRealPath()::startsWith)) {
                    throw new IllegalArgumentException("Project directory cannot be safely inspected");
                }
            }
        } catch (IOException | SecurityException e) {
            throw new IllegalArgumentException("Project directory cannot be safely inspected");
        }
    }

    private static Path canonicalRoot(String value) {
        try {
            Path root = Path.of(value).toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Configured project root is not a directory");
            }
            return root;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Configured project root cannot be resolved", e);
        }
    }
}
