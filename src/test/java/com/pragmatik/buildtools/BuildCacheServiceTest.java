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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BuildCacheService}.
 */
class BuildCacheServiceTest {

    private BuildCacheService service;
    private BuildToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BuildToolProvider();
        service = new BuildCacheService(provider);
    }

    @Test
    void testAnalyzeCacheHealthMavenNoCache(@TempDir Path tempDir) throws IOException {
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

        String result = service.analyzeCacheHealth(tempDir.toString(), "maven");
        assertNotNull(result);
        assertTrue(result.contains("\"cacheHealth\""));
        assertTrue(result.contains("\"status\""));
        assertTrue(result.contains("\"findings\""));
        assertTrue(result.contains("No build cache extension detected"));
    }

    @Test
    void testAnalyzeCacheHealthMavenWithCache(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <extensions>
                            <extension>
                                <groupId>org.apache.maven.extensions</groupId>
                                <artifactId>maven-build-cache-extension</artifactId>
                                <version>1.2.0</version>
                            </extension>
                        </extensions>
                    </build>
                </project>
                """);

        String result = service.analyzeCacheHealth(tempDir.toString(), "maven");
        assertNotNull(result);
        assertTrue(result.contains("Build cache extension configured"));
    }

    @Test
    void testAnalyzeCacheHealthGradleNoCache(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                """);

        String result = service.analyzeCacheHealth(tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"gradle\""));
        assertTrue(result.contains("Build cache not enabled"));
    }

    @Test
    void testAnalyzeCacheHealthGradleOptimized(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                """);
        Files.writeString(
                tempDir.resolve("gradle.properties"),
                """
                org.gradle.caching=true
                org.gradle.configuration-cache=true
                org.gradle.parallel=true
                org.gradle.jvmargs=-Xmx2g
                """);

        String result = service.analyzeCacheHealth(tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("Build cache enabled"));
        assertTrue(result.contains("Configuration cache enabled"));
        assertTrue(result.contains("Parallel execution enabled"));
    }

    @Test
    void testAnalyzeCacheHealthSbt(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.sbt"),
                """
                name := "test"
                version := "1.0.0"
                """);

        String result = service.analyzeCacheHealth(tempDir.toString(), "sbt");
        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"sbt\""));
    }

    @Test
    void testOptimizeBuildCacheMaven(@TempDir Path tempDir) throws IOException {
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

        String result = service.optimizeBuildCache(tempDir.toString(), "maven");
        assertNotNull(result);
        assertTrue(result.contains("\"optimizations\""));
        assertTrue(result.contains("Build Daemon"));
        assertTrue(result.contains("\"optimizationPotential\""));
    }

    @Test
    void testOptimizeBuildCacheGradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"), """
                plugins { id 'java' }
                """);

        String result = service.optimizeBuildCache(tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("\"optimizations\""));
        assertTrue(result.contains("Build Cache"));
    }

    @Test
    void testOptimizeBuildCacheSbt(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.sbt"), """
                name := "test"
                """);

        String result = service.optimizeBuildCache(tempDir.toString(), "sbt");
        assertNotNull(result);
        assertTrue(result.contains("\"optimizations\""));
        assertTrue(result.contains("Coursier"));
    }

    @Test
    void testAnalyzeInvalidDir() {
        String result = service.analyzeCacheHealth("/nonexistent", null);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testOptimizeInvalidDir() {
        String result = service.optimizeBuildCache("/nonexistent", null);
        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
    }

    @Test
    void testAutoDetectTool(@TempDir Path tempDir) throws IOException {
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

        String result = service.analyzeCacheHealth(tempDir.toString(), null);
        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"maven\""));
    }
}
