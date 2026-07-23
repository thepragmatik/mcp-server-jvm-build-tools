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

    // ─── listBuildResources ──────────────────────────────────────────────

    @Test
    void testListBuildResourcesMaven(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myproject</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String result = service.listBuildResources(tempDir.toString());
        String projectName = tempDir.getFileName().toString();

        assertNotNull(result);
        assertTrue(result.contains("\"project\":\"" + projectName + "\""));
        assertTrue(result.contains("\"resourceCount\":5"));
        assertTrue(result.contains("\"resources\""));
        assertTrue(result.contains("build://" + projectName + "/config"));
        assertTrue(result.contains("build://" + projectName + "/dependencies"));
        assertTrue(result.contains("build://" + projectName + "/output"));
        assertTrue(result.contains("build://" + projectName + "/test-results"));
        assertTrue(result.contains("build://" + projectName + "/tool-info"));
        assertTrue(result.contains("\"detectedTool\":\"maven\""));
    }

    @Test
    void testListBuildResourcesGradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins {
                    id 'java'
                }
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "");

        String result = service.listBuildResources(tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"detectedTool\":\"gradle\""));
        assertTrue(result.contains("\"resourceCount\":5"));
    }

    @Test
    void testListBuildResourcesInvalidDir() {
        String result = service.listBuildResources("/nonexistent/path/to/project");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve project directory"));
    }

    @Test
    void testListBuildResourcesNonDirectory(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("notadir.txt");
        Files.writeString(file, "test");

        String result = service.listBuildResources(file.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Project directory is not valid"));
    }

    // ─── readBuildResource ───────────────────────────────────────────────

    @Test
    void testReadBuildResourceConfig(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
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

        String resourceUri = "build://" + projectName + "/config";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"uri\":\"" + resourceUri + "\""));
        assertTrue(result.contains("\"contentType\":\"text/plain\""));
        assertTrue(result.contains("\"files\""));
        assertTrue(result.contains("\"available\":true"));
    }

    @Test
    void testReadBuildResourceDependencies(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
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
                            <artifactId>spring-boot-starter</artifactId>
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

        String resourceUri = "build://" + projectName + "/dependencies";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"application/json\""));
        assertTrue(result.contains("\"dependencies\""));
        assertTrue(result.contains("spring-boot-starter"));
        assertTrue(result.contains("guava"));
        assertTrue(result.contains("\"available\":true"));
    }

    @Test
    void testReadBuildResourceToolInfo(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
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

        String resourceUri = "build://" + projectName + "/tool-info";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"application/json\""));
        assertTrue(result.contains("\"tool\""));
        assertTrue(result.contains("\"name\":\"maven\""));
    }

    @Test
    void testReadBuildResourceOutput(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        String resourceUri = "build://" + projectName + "/output";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"contentType\":\"application/json\""));
        assertTrue(result.contains("\"available\":false"));
        assertTrue(result.contains("Build output is available on-demand"));
    }

    @Test
    void testReadBuildResourceTestResults(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        String resourceUri = "build://" + projectName + "/test-results";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"available\":false"));
        assertTrue(result.contains("Build output is available on-demand"));
    }

    @Test
    void testReadBuildResourceUnknownUri(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        String result = service.readBuildResource("build://" + projectName + "/unknown", tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Unknown resource URI"));
        assertTrue(result.contains("list_build_resources"));
    }

    @Test
    void testReadBuildResourceInvalidDir() {
        String result = service.readBuildResource("build://test/config", "/nonexistent/path");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve project directory"));
    }

    // ─── Edge cases ──────────────────────────────────────────────────────

    @Test
    void testListBuildResourcesReturnsValidJson(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        String result = service.listBuildResources(tempDir.toString());

        // Basic JSON validity: starts with {, ends with }
        assertTrue(result.startsWith("{"), "Result should be valid JSON object");
        assertTrue(result.endsWith("}"), "Result should be valid JSON object");
    }

    @Test
    void testReadBuildResourceConfigWithBuildGradle(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins {
                    id 'java'
                }
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "");
        Files.writeString(tempDir.resolve("gradle.properties"), "org.gradle.caching=true");

        String resourceUri = "build://" + projectName + "/config";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"fileCount\""));
        // Should find build.gradle, settings.gradle, and gradle.properties
        assertTrue(result.contains("build.gradle"));
    }

    @Test
    void testReadBuildResourceConfigNoBuildFiles(@TempDir Path tempDir) throws IOException {
        String projectName = tempDir.getFileName().toString();
        // No build files at all

        String resourceUri = "build://" + projectName + "/config";
        String result = service.readBuildResource(resourceUri, tempDir.toString());

        assertNotNull(result);
        assertTrue(result.contains("\"fileCount\":0"));
        assertTrue(result.contains("\"available\":false"));
    }
}
