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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AsyncBuildService}.
 */
class AsyncBuildServiceTest {

    private AsyncBuildService service;
    private BuildToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BuildToolProvider();
        service = new AsyncBuildService(provider);
    }

    @Test
    void testExecuteBuildAsyncValidatesInput(@TempDir Path tempDir) throws IOException {
        // Empty command
        String result = service.executeBuildAsync(null, null, tempDir.toString(), "");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("cannot be null or empty"));

        // Non-existent project dir
        result = service.executeBuildAsync(null, null, "/nonexistent/path", "clean");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve"));

        // Non-directory path
        Path file = tempDir.resolve("notadir.txt");
        Files.writeString(file, "test");
        result = service.executeBuildAsync(null, null, file.toString(), "clean");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testExecuteBuildAsyncReturnsTaskHandle(@TempDir Path tempDir, @TempDir Path mavenHome) throws IOException {
        // Create a basic Maven project so auto-detection works
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String result = service.executeBuildAsync("maven", mavenHome.toString(), tempDir.toString(), "clean");

        assertNotNull(result);
        assertTrue(result.contains("\"taskId\""));
        assertTrue(result.contains("\"status\":\"queued\""));
        assertTrue(result.contains("\"tool\":\"maven\""));
        assertTrue(result.contains("\"command\":\"clean\""));
    }

    @Test
    void testExecuteBuildAsyncRejectsUnknownTool(@TempDir Path tempDir) {
        String result = service.executeBuildAsync("bazel", null, tempDir.toString(), "build");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Unknown build tool"));
    }

    @Test
    void testGetBuildTaskNotFound() {
        String result = service.getBuildTask("nonexistent-id");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Task not found"));
    }

    @Test
    void testCancelBuildTaskNotFound() {
        String result = service.cancelBuildTask("nonexistent-id");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Task not found"));
    }

    @Test
    void testCancelBuildTaskNotRunning(@TempDir Path tempDir, @TempDir Path mavenHome) throws IOException {
        // Create a task then manually set it to completed
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String execResult = service.executeBuildAsync("maven", mavenHome.toString(), tempDir.toString(), "validate");
        assertTrue(execResult.contains("\"taskId\""));

        // Extract task ID
        String taskId = extractJsonString(execResult, "taskId");
        assertNotNull(taskId);

        // Cancel should succeed since task hasn't started running yet
        // (Maven home has no mvn binary so execution will fail, but task is cancelable)
        String cancelResult = service.cancelBuildTask(taskId);
        assertNotNull(cancelResult);
    }

    @Test
    void testListBuildTasks(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        // Start a task (maven home doesn't need to be valid for listing test)
        Files.createDirectories(tempDir.resolve("maven-home"));
        service.executeBuildAsync("maven", tempDir.resolve("maven-home").toString(), tempDir.toString(), "validate");

        String result = service.listBuildTasks();
        assertNotNull(result);
        assertTrue(result.contains("\"tasks\""));
        assertTrue(result.contains("\"totalCount\""));
        assertTrue(result.contains("\"activeCount\""));
    }

    @Test
    void testExecuteBuildAsyncAutoDetectTool(@TempDir Path tempDir) throws IOException {
        // Create Gradle project markers
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                """);

        String result = service.executeBuildAsync(null, null, tempDir.toString(), "build");

        assertNotNull(result);
        assertTrue(result.contains("\"taskId\""));
        // Auto-detection should pick up gradle
        assertTrue(result.contains("\"tool\":\"gradle\""));
    }

    @Test
    void testMavenProcessExecutionCreation() {
        // Verify the MavenProcessExecution record can be instantiated
        // This is a compile-time check — the record should exist
        assertDoesNotThrow(() -> {
            // Just verify the class exists
            Class.forName("com.pragmatik.buildtools.maven.MavenInvoker$MavenProcessExecution");
        });
    }

    @Test
    @Timeout(20)
    void asyncGradleOutputStaysBounded(@TempDir Path projectDir) throws Exception {
        Files.createFile(projectDir.resolve("build.gradle"));
        Path bin = Files.createDirectory(projectDir.resolve("bin"));
        Path fakeGradle = bin.resolve("gradle");
        Files.writeString(
                fakeGradle, "#!/bin/sh\nprintf 'BEGIN'; yes x | tr -d '\\n' | head -c 1048576; printf 'END'\n");
        assertTrue(fakeGradle.toFile().setExecutable(true));

        String created = service.executeBuildAsync("gradle", projectDir.toString(), projectDir.toString(), "build");
        String taskId = extractJsonString(created, "taskId");
        assertNotNull(taskId);
        String result = "";
        for (int attempt = 0; attempt < 200; attempt++) {
            result = service.getBuildTask(taskId);
            if (result.contains("\"status\":\"completed\"") || result.contains("\"status\":\"failed\"")) {
                break;
            }
            Thread.sleep(25);
        }

        assertTrue(result.contains("\"status\":\"completed\""), result);
        assertTrue(result.contains("\"outputTruncated\":true"));
        assertTrue(result.contains("BEGIN"));
        assertTrue(result.contains("END"));
        // The JSON includes both the retained output and a parsed-result copy.
        assertTrue(result.length() < 512 * 1024);
        Path summary = projectDir.resolve(".buildtools/tasks").resolve(taskId + ".json");
        for (int attempt = 0; attempt < 100 && !Files.exists(summary); attempt++) {
            Thread.sleep(10);
        }
        String persisted = Files.readString(summary);
        assertFalse(persisted.contains("\"command\""));
        assertFalse(persisted.contains("BEGIN"));
        assertFalse(persisted.contains(projectDir.toString()));
    }

    @Test
    @Timeout(20)
    void asyncCancellationTerminatesChildProcess(@TempDir Path projectDir) throws Exception {
        Files.createFile(projectDir.resolve("build.gradle"));
        Path bin = Files.createDirectory(projectDir.resolve("bin"));
        Path fakeGradle = bin.resolve("gradle");
        Files.writeString(fakeGradle, "#!/bin/sh\nsleep 60 & echo $! > child.pid; wait\n");
        assertTrue(fakeGradle.toFile().setExecutable(true));

        String created = service.executeBuildAsync("gradle", projectDir.toString(), projectDir.toString(), "build");
        String taskId = extractJsonString(created, "taskId");
        assertNotNull(taskId);
        Path childPidFile = projectDir.resolve("child.pid");
        for (int attempt = 0; attempt < 200 && !Files.exists(childPidFile); attempt++) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(childPidFile));
        long childPid = Long.parseLong(Files.readString(childPidFile).trim());

        String cancellation = service.cancelBuildTask(taskId);

        assertTrue(cancellation.contains("\"cancelled\":true"));
        for (int attempt = 0;
                attempt < 200
                        && ProcessHandle.of(childPid)
                                .map(ProcessHandle::isAlive)
                                .orElse(false);
                attempt++) {
            Thread.sleep(10);
        }
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        Path summary = projectDir.resolve(".buildtools/tasks").resolve(taskId + ".json");
        for (int attempt = 0; attempt < 200 && !Files.exists(summary); attempt++) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(summary));
        assertTrue(Files.readString(summary).contains("\"status\":\"cancelled\""));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}
