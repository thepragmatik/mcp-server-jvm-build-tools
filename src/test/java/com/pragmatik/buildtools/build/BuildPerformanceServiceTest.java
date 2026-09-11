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

import com.pragmatik.buildtools.maven.MavenBuildTool;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildPerformanceServiceTest {

    private BuildPerformanceService service;

    @BeforeEach
    void setUp() {
        BuildToolProvider provider = new BuildToolProvider();
        service = new BuildPerformanceService(provider);
    }

    @AfterEach
    void restoreResolver() {
        BuildPerformanceService.mavenHomeResolver = com.pragmatik.buildtools.maven.MavenHomeResolver::resolveMavenHome;
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

        String result = service.analyzeBuildPerformance(tempDir.toString(), "maven");
        assertNotNull(result);
        assertTrue(result.contains("suggestions"));
    }

    @Test
    void testAnalyzeBuildPerformanceGradleWithProperties(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("settings.gradle"), "");
        Files.writeString(tempDir.resolve("gradle.properties"), "org.gradle.parallel=true\norg.gradle.caching=true\n");

        String result = service.analyzeBuildPerformance(tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("suggestions"));
        // Should NOT suggest parallel/caching since already configured
        assertFalse(result.contains("org.gradle.parallel=true"), "Should not suggest parallel when already configured");
    }

    @Test
    void testAnalyzeBuildPerformanceGradleMissingProperties(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }");
        Files.writeString(tempDir.resolve("settings.gradle"), "");

        String result = service.analyzeBuildPerformance(tempDir.toString(), "gradle");
        assertNotNull(result);
        assertTrue(result.contains("No gradle.properties found"), "Should suggest creating gradle.properties");
    }

    @Test
    void testAnalyzeBuildPerformanceSbt(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.sbt"), "name := \"test\"\nversion := \"1.0\"");

        String result = service.analyzeBuildPerformance(tempDir.toString(), "sbt");
        assertNotNull(result);
        assertTrue(result.contains("Coursier"), "SBT should suggest Coursier for faster resolution");
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

    // ─── profileBuild ────────────────────────────────────────────────────

    @Test
    void testProfileBuildInvalidDir() {
        String result = service.profileBuild(null, null, "/nonexistent/path", "test");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve project directory"));
    }

    @Test
    void testProfileBuildNonDirectory(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("notadir.txt");
        Files.writeString(file, "test");

        String result = service.profileBuild(null, null, file.toString(), "test");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Project directory is not valid"));
    }

    @Test
    void testProfileBuildInvalidBuildToolHome(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        String result = service.profileBuild("maven", "/nonexistent/maven/home", tempDir.toString(), "validate");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(result.contains("Cannot resolve build tool home"));
    }

    @Test
    void testProfileBuildReturnsPerformanceMetrics(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        // The server resolves a real Maven installation (MAVEN_HOME env var,
        // maven.home system property, or mvn on PATH). In the test JVM we inject
        // the resolved home explicitly so the test is hermetic even when the
        // invoking shell has no Maven on PATH.
        BuildPerformanceService.mavenHomeResolver =
                () -> Optional.of(com.pragmatik.buildtools.transport.TestUtils.resolveMavenHome());

        // profileBuild with "validate" — a lightweight Maven phase that does no work
        String result = service.profileBuild("maven", null, tempDir.toString(), "validate");

        assertNotNull(result);
        // Should return JSON with performance fields even if the build tool isn't available
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("\"tool\":\"maven\""));
        assertTrue(result.contains("\"command\":\"validate\""));
    }

    @Test
    void testProfileBuildWithBlankBuildToolHome(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        // Blank buildToolHome should be treated as not provided
        BuildPerformanceService.mavenHomeResolver =
                () -> Optional.of(com.pragmatik.buildtools.transport.TestUtils.resolveMavenHome());
        String result = service.profileBuild("maven", "  ", tempDir.toString(), "validate");

        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"maven\""));
    }

    @Test
    void testProfileBuildAutoDetectGradle(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                """);
        Files.writeString(tempDir.resolve("settings.gradle"), "");

        // Auto-detect should resolve to gradle
        String result = service.profileBuild(null, null, tempDir.toString(), "build");

        assertNotNull(result);
        assertTrue(result.contains("\"tool\":\"gradle\""));
        assertTrue(result.contains("\"command\":\"build\""));
    }

    // ─── Issue #188: profile_build validation & history hygiene ─────────

    private Path writePom(Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        return tempDir;
    }

    @Test
    void testProfileBuildWithoutBuildToolHomeReturnsClearErrorAndWritesNoHistory(@TempDir Path tempDir)
            throws IOException {
        writePom(tempDir);
        // No Maven installation resolvable in this test scenario
        BuildPerformanceService.mavenHomeResolver = Optional::empty;

        String result = service.profileBuild("maven", null, tempDir.toString(), "clean test");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false"));
        assertTrue(
                result.contains("Maven requires buildToolHome. Specify a Maven installation directory."),
                "Should return the SAME clear validation error as execute_build_command");
        assertFalse(result.contains("durationSeconds"), "Must not record a fake 0.0s build");
        assertFalse(
                Files.exists(tempDir.resolve(".buildtools/history/maven_clean_test.json")),
                "Validation failures must not write history entries");
    }

    @Test
    void testProfileBuildWithBuildToolHomeSucceedsAndWritesHistory(@TempDir Path tempDir) throws IOException {
        // A stub Maven home so the build executes (command validation still applies,
        // and the invoker fails on the stub — the point is a genuine execution
        // attempt that lands in history, not a silent validation failure).
        Path mavenHome = tempDir.resolve("maven-stub");
        Files.createDirectories(mavenHome.resolve("bin"));
        Path mvn = mavenHome.resolve("bin/mvn");
        Files.writeString(mvn, "#!/bin/sh\nexit 0\n");
        mvn.toFile().setExecutable(true);

        writePom(tempDir);

        String result = service.profileBuild("maven", mavenHome.toString(), tempDir.toString(), "validate");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"), "Genuine build execution should succeed: " + result);
        assertTrue(
                Files.exists(tempDir.resolve(".buildtools/history/maven_validate.json")),
                "Genuine build execution must write a history entry");
    }

    @Test
    void testProfileBuildMavenHomeEnvOnlyPathSucceeds(@TempDir Path tempDir) throws IOException {
        Path mavenHome = tempDir.resolve("maven-stub-env");
        Files.createDirectories(mavenHome.resolve("bin"));
        Path mvn = mavenHome.resolve("bin/mvn");
        Files.writeString(mvn, "#!/bin/sh\nexit 0\n");
        mvn.toFile().setExecutable(true);
        // No explicit buildToolHome — the resolver stands in for a MAVEN_HOME env var
        // set in the invoking shell (inherited by the stdio-launched server process).
        BuildPerformanceService.mavenHomeResolver = () -> Optional.of(mavenHome.toString());

        writePom(tempDir);

        String result = service.profileBuild("maven", null, tempDir.toString(), "validate");

        assertNotNull(result);
        assertTrue(result.contains("\"success\":true"), "MAVEN_HOME-only path should work: " + result);
    }

    @Test
    void testMavenHomeResolverPrefersEnvThenPropThenPath(@TempDir Path tempDir) throws IOException {
        Path envHome = Files.createDirectories(tempDir.resolve("env-home"));
        Path propHome = Files.createDirectories(tempDir.resolve("prop-home"));

        Optional<String> resolved = com.pragmatik.buildtools.maven.MavenHomeResolver.resolveMavenHome(
                envHome.toString(), null, propHome.toString());
        assertTrue(resolved.isPresent());
        assertEquals(envHome.toRealPath().toString(), resolved.get());

        resolved = com.pragmatik.buildtools.maven.MavenHomeResolver.resolveMavenHome(null, null, propHome.toString());
        assertTrue(resolved.isPresent());
        assertEquals(propHome.toRealPath().toString(), resolved.get());

        // Non-existent candidates fall through to empty
        assertTrue(com.pragmatik.buildtools.maven.MavenHomeResolver.resolveMavenHome(
                        "/nonexistent/maven", "/nonexistent/bin", null)
                .isEmpty());
    }

    @Test
    void testMavenHomeResolverFromPathLookup(@TempDir Path tempDir) throws IOException {
        Path mavenHome =
                Files.createDirectories(tempDir.resolve("apache-maven-3.9.9").resolve("bin"));
        Path mvn = mavenHome.resolve("mvn");
        Files.writeString(mvn, "#!/bin/sh\nexit 0\n");
        mvn.toRealPath().toFile().setExecutable(true);

        Optional<String> resolved = com.pragmatik.buildtools.maven.MavenHomeResolver.resolveMavenHome(
                null, tempDir.resolve("apache-maven-3.9.9").resolve("bin").toString(), null);
        assertTrue(resolved.isPresent(), "mvn on PATH should resolve to the installation dir");
        assertEquals(tempDir.resolve("apache-maven-3.9.9").toRealPath().toString(), resolved.get());
    }

    @Test
    void testRequireMavenHomeThrowsCanonicalErrorWhenUnresolvable() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            MavenBuildTool.requireMavenHome(null, Optional::empty);
        });
        assertEquals("Maven requires buildToolHome. Specify a Maven installation directory.", ex.getMessage());

        // Explicit buildToolHome is returned verbatim
        assertEquals("/opt/maven", MavenBuildTool.requireMavenHome("/opt/maven", Optional::empty));
    }
}
