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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildPerformanceServiceTest {

    private BuildPerformanceService service;

    @BeforeEach
    void setUp() {
        BuildToolProvider provider = new BuildToolProvider(
                List.of(new MavenBuildTool(), new GradleBuildTool(), new SbtBuildTool()));
        service = new BuildPerformanceService(provider);
    }

    @Test
    void testFormatDuration() throws Exception {
        Method m = BuildPerformanceService.class.getDeclaredMethod("formatDuration", Duration.class);
        m.setAccessible(true);

        assertEquals("5s", m.invoke(service, Duration.ofSeconds(5)));
        assertEquals("2m 30s", m.invoke(service, Duration.ofSeconds(150)));
        assertEquals("1h 5m 30s", m.invoke(service, Duration.ofSeconds(3930)));
    }

    @Test
    void testAnalyzeBuildPerformanceMaven(@TempDir Path tempDir) throws IOException {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), pom);

        String result = service.analyzeBuildPerformance(
                tempDir.toString(), "maven");
        assertNotNull(result);
        assertTrue(result.contains("suggestions"));
    }

    @Test
    void testAnalyzeBuildPerformanceGradleWithProperties(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"),
                "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("settings.gradle"), "");
        Files.writeString(tempDir.resolve("gradle.properties"),
                "org.gradle.parallel=true\norg.gradle.caching=true\n");

        String result = service.analyzeBuildPerformance(
                tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("suggestions"));
        // Should NOT suggest parallel/caching since already configured
        assertFalse(result.contains("org.gradle.parallel=true"),
                "Should not suggest parallel when already configured");
    }

    @Test
    void testAnalyzeBuildPerformanceGradleMissingProperties(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"),
                "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("settings.gradle"), "");

        String result = service.analyzeBuildPerformance(
                tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("No gradle.properties found"),
                "Should suggest creating gradle.properties");
    }

    @Test
    void testAnalyzeBuildPerformanceSbt(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.sbt"),
                "name := \"test\"\nversion := \"1.0\"");

        String result = service.analyzeBuildPerformance(
                tempDir.toString(), "sbt");
        assertNotNull(result);
        assertTrue(result.contains("Coursier"),
                "SBT should suggest Coursier for faster resolution");
    }

    @Test
    void testOptimizationPotentialLevels(@TempDir Path tempDir) throws IOException {
        // Maven with standard POM — should have MEDIUM potential
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0</version>
                </project>
                """);

        String result = service.analyzeBuildPerformance(tempDir.toString(), "maven");
        assertTrue(result.contains("optimizationPotential"));
    }
}
