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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnchoredProjectFileReaderTest {

    @TempDir
    Path temporary;

    @Test
    void readsNestedRegularFileWithOneBytePastLimit() throws IOException {
        assumeSecureDirectories();
        Path project = Files.createDirectories(temporary.toRealPath().resolve("parent/project"));
        Files.writeString(project.resolve("pom.xml"), "abcdef", StandardCharsets.UTF_8);

        assertThat(AnchoredProjectFileReader.read(project, "pom.xml", 4))
                .isEqualTo("abcde".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsSymlinkedProjectComponentAndFinalFile() throws IOException {
        assumeSecureDirectories();
        Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
        Files.writeString(outside.resolve("pom.xml"), "SYNTHETIC_PRIVATE_CANARY");
        Path parent = Files.createDirectory(temporary.toRealPath().resolve("parent"));
        Files.createSymbolicLink(parent.resolve("project"), outside);

        assertThatThrownBy(() -> AnchoredProjectFileReader.read(parent.resolve("project"), "pom.xml", 100))
                .isInstanceOf(IOException.class);

        Path regularProject = Files.createDirectory(parent.resolve("regular"));
        Files.createSymbolicLink(regularProject.resolve("pom.xml"), outside.resolve("pom.xml"));
        assertThatThrownBy(() -> AnchoredProjectFileReader.read(regularProject, "pom.xml", 100))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsDirectoryReplacementWithOutsideSymlink() throws IOException {
        assumeSecureDirectories();
        Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
        Files.writeString(outside.resolve("pom.xml"), "SYNTHETIC_PRIVATE_CANARY");
        Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "safe");
        Files.move(project, temporary.toRealPath().resolve("moved"));
        Files.createSymbolicLink(project, outside);

        assertThatThrownBy(() -> AnchoredProjectFileReader.read(project, "pom.xml", 100))
                .isInstanceOf(IOException.class);
    }

    @Test
    void heldProjectHandleIgnoresLaterPathReplacementAndExternalMarkers() throws IOException {
        assumeSecureDirectories();
        Path root = temporary.toRealPath();
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("build.gradle"), "SYNTHETIC_PRIVATE_CANARY");
        Path project = Files.createDirectory(root.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "safe");

        try (AnchoredProjectFileReader.ProjectDirectory opened = AnchoredProjectFileReader.open(project)) {
            Files.move(project, root.resolve("moved"));
            Files.createSymbolicLink(project, outside);

            assertThat(opened.readIfPresent("pom.xml", 100)).isEqualTo("safe".getBytes(StandardCharsets.UTF_8));
            assertThat(opened.readIfPresent("build.gradle", 100)).isNull();
            Files.createSymbolicLink(root.resolve("moved/build.gradle"), outside.resolve("build.gradle"));
            assertThatThrownBy(() -> opened.readIfPresent("build.gradle", 100)).isInstanceOf(IOException.class);
            Files.createSymbolicLink(root.resolve("moved/build.gradle.kts"), root.resolve("missing.gradle"));
            assertThatThrownBy(() -> opened.readIfPresent("build.gradle.kts", 100))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void heldMarkerChecksIgnoreReplacedProjectPathAndRejectLocalSymlinks() throws IOException {
        assumeSecureDirectories();
        Path root = temporary.toRealPath();
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("build.gradle"), "SYNTHETIC_PRIVATE_CANARY");
        Path project = Files.createDirectory(root.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "safe");

        try (AnchoredProjectFileReader.ProjectDirectory opened = AnchoredProjectFileReader.open(project)) {
            Files.move(project, root.resolve("moved"));
            Files.createSymbolicLink(project, outside);

            opened.requireSafeMarker("pom.xml");
            opened.requireSafeMarker("build.gradle");
            opened.requireSafeNestedMarker("project", "build.properties");

            Files.createSymbolicLink(root.resolve("moved/build.gradle"), outside.resolve("build.gradle"));
            assertThatThrownBy(() -> opened.requireSafeMarker("build.gradle")).isInstanceOf(IOException.class);

            Files.createSymbolicLink(root.resolve("moved/project"), outside);
            assertThatThrownBy(() -> opened.requireSafeNestedMarker("project", "build.properties"))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsInvalidFileNames() throws IOException {
        Path project = Files.createDirectory(temporary.toRealPath().resolve("project"));
        for (String filename : new String[] {"", ".", "..", "child/file", "child\\file"}) {
            assertThatThrownBy(() -> AnchoredProjectFileReader.read(project, filename, 100))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsNamedPipeBeforeReadOnlyOpen() throws Exception {
        assumeSecureDirectories();
        Path project = Files.createDirectory(temporary.toRealPath().resolve("fifo-project"));
        Path marker = project.resolve("pom.xml");
        Process command;
        try {
            command = new ProcessBuilder("mkfifo", marker.toString()).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "mkfifo is unavailable");
            return;
        }
        assumeTrue(command.waitFor() == 0, "mkfifo is unavailable");

        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertThatThrownBy(() -> AnchoredProjectFileReader.read(project, "pom.xml", 100))
                        .isInstanceOf(IOException.class));
    }

    private void assumeSecureDirectories() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(temporary.getRoot())) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>);
        }
    }
}
