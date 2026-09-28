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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAccessPolicyTest {
    @TempDir
    Path temporary;

    @Test
    void requiresExplicitRoot() {
        assertThrows(
                IllegalArgumentException.class, () -> new ProjectAccessPolicy("").requireAllowed(temporary.toString()));
    }

    @Test
    void acceptsProjectWithinConfiguredRoot() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        assertEquals(project.toRealPath(), new ProjectAccessPolicy(root.toString()).requireAllowed(project.toString()));
    }

    @Test
    void resolvesRelativeAliasWithinFirstRoot() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        assertEquals(project.toRealPath(), new ProjectAccessPolicy(root.toString()).requireAllowed("project"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectAccessPolicy(root.toString()).requireAllowed("../outside"));
    }

    @Test
    void rejectsSiblingWithSamePrefix() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path sibling = Files.createDirectory(temporary.resolve("allowed-other"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectAccessPolicy(root.toString()).requireAllowed(sibling.toString()));
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, outside);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectAccessPolicy(root.toString()).requireAllowed(link.toString()));
    }

    @Test
    void rejectsSymlinkedBuildFileOutsideRoot() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        Path outside = Files.writeString(temporary.resolve("outside-pom.xml"), "<project/>");
        Files.createSymbolicLink(project.resolve("pom.xml"), outside);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectAccessPolicy(root.toString()).requireAllowed("project"));
    }

    @Test
    void rejectsPresentSymlinkedMarkersWithGenericError() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(temporary.getRoot())) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>);
        }
        Path root = Files.createDirectory(temporary.toRealPath().resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        Path target = Files.writeString(root.resolve("inside-pom.xml"), "<project/>");
        Path marker = project.resolve("pom.xml");
        Files.createSymbolicLink(marker, target);
        ProjectAccessPolicy policy = new ProjectAccessPolicy(root.toString());

        IllegalArgumentException first =
                assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed("project"));
        assertEquals("Project directory cannot be safely inspected", first.getMessage());

        Files.delete(marker);
        Files.createSymbolicLink(marker, project.resolve("missing-pom.xml"));
        IllegalArgumentException broken =
                assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed("project"));
        assertEquals(first.getMessage(), broken.getMessage());
    }

    @Test
    void rejectsNestedMarkerDirectorySymlink() throws IOException {
        Path root = Files.createDirectory(temporary.toRealPath().resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
        Files.writeString(outside.resolve("build.properties"), "SYNTHETIC_PRIVATE_CANARY");
        Files.createSymbolicLink(project.resolve("project"), outside);

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectAccessPolicy(root.toString()).requireAllowed(project.toString()));
        assertEquals("Project directory cannot be safely inspected", denied.getMessage());
    }

    @Test
    void allowsUnrelatedRegularFileNamedProject() throws IOException {
        Path root = Files.createDirectory(temporary.toRealPath().resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("maven-project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("project"), "ordinary file");

        assertEquals(project.toRealPath(), new ProjectAccessPolicy(root.toString()).requireAllowed(project.toString()));
    }

    @Test
    void compatibilityFallbackPreservesSymlinkEscapeDenial() throws IOException {
        Path root = Files.createDirectory(temporary.toRealPath().resolve("allowed"));
        Path project = Files.createDirectory(root.resolve("project"));
        Path outside = Files.writeString(temporary.toRealPath().resolve("outside-pom.xml"), "<project/>");
        Files.createSymbolicLink(project.resolve("pom.xml"), outside);

        ProjectAccessPolicy policy = new ProjectAccessPolicy(root.toString());
        IllegalArgumentException denied =
                assertThrows(IllegalArgumentException.class, () -> policy.checkBuildMarkersCompatibly(project));
        assertEquals("Project directory cannot be safely inspected", denied.getMessage());
    }
}
