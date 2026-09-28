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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
