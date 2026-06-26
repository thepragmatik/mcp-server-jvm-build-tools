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
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BuildResourceService}.
 */
class BuildResourceServiceTest {

    private BuildResourceService service;
    private BuildToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BuildToolProvider();
        service = new BuildResourceService(provider);
    }

    // ─── listBuildResources ─────────────────────────────────────────────

    @Test
    void testListBuildResourcesMaven(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String result = service.listBuildResources(tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"detectedTool\":\"maven\""));
        assertTrue(result.contains("\"resourceCount\":5"));
        String projectName = tempDir.getFileName().toString();
        assertTrue(result.contains("build://" + projectName + "/config"));
        assertTrue(result.contains("build://" + projectName + "/dependencies"));
        assertTrue(result.contains("build://" + projectName + "/output"));
        assertTrue(result.contains("build://" + projectName + "/test-results"));
        assertTrue(result.contains("build://" + projectName + "/tool-info"));
    }

    @Test
    void testListBuildResourcesInvalidDir() {
        String result = service.listBuildResources("/nonexistent/path");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve project directory"));
    }

    @Test
    void testListBuildResourcesNotADirectory(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("not-a-dir.txt");
        Files.writeString(file, "this is a file");
        String result = service.listBuildResources(file.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Project directory is not valid"));
    }

    // ─── readBuildResource — config ─────────────────────────────────────

    @Test
    void testReadBuildResourceConfig(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/config", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"text/plain\""));
        assertTrue(result.contains("\"fileCount\":1"));
        assertTrue(result.contains("\"available\":true"));
        assertTrue(result.contains("pom.xml"));
    }

    @Test
    void testReadBuildResourceConfigMultipleFiles(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("build.sbt"), "name := \"test\"");

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/config", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"fileCount\":3"));
        assertTrue(result.contains("pom.xml"));
        assertTrue(result.contains("build.gradle"));
        assertTrue(result.contains("build.sbt"));
    }

    @Test
    void testReadBuildResourceConfigEmpty(@TempDir Path tempDir) {
        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/config", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"fileCount\":0"));
        assertTrue(result.contains("\"available\":false"));
    }

    // ─── readBuildResource — dependencies ───────────────────────────────

    @Test
    void testReadBuildResourceDependencies(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>33.0.0-jre</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/dependencies", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"dependencyCount\":2"));
        assertTrue(result.contains("org.springframework.boot"));
        assertTrue(result.contains("spring-boot-starter-web"));
        assertTrue(result.contains("com.google.guava"));
        assertTrue(result.contains("\"available\":true"));
    }

    @Test
    void testReadBuildResourceDependenciesNoPom(@TempDir Path tempDir) {
        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/dependencies", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"dependencyCount\":0"));
        assertTrue(result.contains("\"available\":false"));
        assertTrue(result.contains("Dependency extraction is currently supported for Maven"));
    }

    // ─── readBuildResource — tool-info ──────────────────────────────────

    @Test
    void testReadBuildResourceToolInfo(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/tool-info", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"application/json\""));
        assertTrue(result.contains("\"tool\""));
        assertTrue(result.contains("\"name\":\"maven\""));
    }

    // ─── readBuildResource — output / test-results ──────────────────────

    @Test
    void testReadBuildResourceOutput(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/output", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"application/json\""));
        assertTrue(result.contains("\"available\":false"));
        assertTrue(result.contains("Build output is available on-demand"));
    }

    @Test
    void testReadBuildResourceTestResults(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/test-results", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"application/json\""));
        assertTrue(result.contains("\"available\":false"));
        assertTrue(result.contains("Build output is available on-demand"));
    }

    // ─── readBuildResource — error cases ────────────────────────────────

    @Test
    void testReadBuildResourceUnknownUri(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/unknown", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Unknown resource URI"));
    }

    @Test
    void testReadBuildResourceInvalidDir() {
        String result = service.readBuildResource("build://test/config", "/nonexistent/path");
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve project directory"));
    }

    // ─── readBuildResource — dependency extraction details ──────────────

    @Test
    void testReadBuildResourceDependenciesExtractsCoordinates(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.16</version>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-api</artifactId>
                            <version>5.12.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        String projectName = tempDir.getFileName().toString();
        String result = service.readBuildResource(
                "build://" + projectName + "/dependencies", tempDir.toString());
        assertNotNull(result);
        assertTrue(result.contains("\"dependencyCount\":2"));
        assertTrue(result.contains("org.slf4j"));
        assertTrue(result.contains("slf4j-api"));
        assertTrue(result.contains("2.0.16"));
        assertTrue(result.contains("org.junit.jupiter"));
        assertTrue(result.contains("junit-jupiter-api"));
        assertTrue(result.contains("\"buildTool\":\"maven\""));
    }
}
