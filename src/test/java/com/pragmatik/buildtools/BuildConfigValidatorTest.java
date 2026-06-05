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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code validate_build_configuration} tool.
 * <p>
 * Tests validation of pom.xml, build.gradle, and build.gradle.kts files
 * for syntactic correctness, required elements, and common issues.
 */
@SpringBootTest
@DisplayName("BuildConfigValidator integration tests")
class BuildConfigValidatorTest {

    @Autowired
    private BuildToolsService buildToolsService;

    // ──────────────────────────────────────────────
    //  pom.xml validation
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("pom.xml validation")
    class PomXmlValidation {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("validates a well-formed pom.xml as valid")
        void validatesWellFormedPom() throws Exception {
            String pom = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId>
                      <artifactId>my-app</artifactId>
                      <version>1.0.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>org.junit.jupiter</groupId>
                          <artifactId>junit-jupiter</artifactId>
                          <version>5.10.0</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pom);

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("\"valid\":true");
            assertThat(result).contains("\"tool\":\"maven\"");
            assertThat(result).contains("pom.xml");
        }

        @Test
        @DisplayName("flags missing required elements in pom.xml")
        void flagsMissingRequiredElements() throws Exception {
            String pom = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pom);

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("groupId");
            assertThat(result).contains("artifactId");
        }

        @Test
        @DisplayName("flags duplicate dependency declarations")
        void flagsDuplicateDependencies() throws Exception {
            String pom = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId>
                      <artifactId>my-app</artifactId>
                      <version>1.0.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>com.google.guava</groupId>
                          <artifactId>guava</artifactId>
                          <version>31.1-jre</version>
                        </dependency>
                        <dependency>
                          <groupId>com.google.guava</groupId>
                          <artifactId>guava</artifactId>
                          <version>33.0.0-jre</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pom);

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("Duplicate dependency");
            assertThat(result).contains("guava");
        }

        @Test
        @DisplayName("handles empty project directory gracefully")
        void handlesEmptyProjectDir(@TempDir Path emptyDir) {
            String result = buildToolsService.validateBuildConfiguration(emptyDir.toString());

            // No build config files found - the valid field should be true (nothing to validate)
            assertThat(result).contains("\"valid\":true");
            assertThat(result).contains("\"issueCount\":0");
        }
    }

    // ──────────────────────────────────────────────
    //  build.gradle validation
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("build.gradle validation")
    class BuildGradleValidation {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("validates a well-formed build.gradle")
        void validatesWellFormedBuildGradle() throws Exception {
            String gradle = """
                    plugins {
                        id 'java'
                    }
                                        
                    repositories {
                        mavenCentral()
                    }
                                        
                    dependencies {
                        implementation 'com.google.guava:guava:31.1-jre'
                        testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
                    }
                    """;
            Files.writeString(projectDir.resolve("build.gradle"), gradle);

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("\"valid\":true");
            assertThat(result).contains("\"tool\":\"gradle\"");
        }

        @Test
        @DisplayName("flags unbalanced braces in build.gradle")
        void flagsUnbalancedBraces() throws Exception {
            String gradle = """
                    plugins {
                        id 'java'
                    // missing closing brace for plugins block
                                        
                    dependencies {
                        implementation 'com.google.guava:guava:31.1-jre'
                    }
                    """;
            Files.writeString(projectDir.resolve("build.gradle"), gradle);

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("Unbalanced braces");
        }

        @Test
        @DisplayName("flags empty build.gradle")
        void flagsEmptyBuildGradle() throws Exception {
            Files.writeString(projectDir.resolve("build.gradle"), "");

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("empty");
        }

        @Test
        @DisplayName("validates build.gradle.kts")
        void validatesBuildGradleKts() throws Exception {
            String gradleKts = """
                    plugins {
                        id("java")
                    }
                                        
                    repositories {
                        mavenCentral()
                    }
                                        
                    dependencies {
                        implementation("com.google.guava:guava:31.1-jre")
                    }
                    """;
            Files.writeString(projectDir.resolve("build.gradle.kts"), gradleKts);

            String result = buildToolsService.validateBuildConfiguration(projectDir.toString());

            assertThat(result).contains("\"valid\":true");
        }
    }

    // ──────────────────────────────────────────────
    //  Edge cases
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("returns error for nonexistent project directory")
        void returnsErrorForNonexistentDir() {
            String result = buildToolsService.validateBuildConfiguration(
                    "/nonexistent/path/12345");

            assertThat(result).contains("\"error\"");
        }

        @Test
        @DisplayName("handles directory that is a file")
        void handlesDirectoryThatIsFile(@TempDir Path tmpDir) throws Exception {
            Path aFile = tmpDir.resolve("not-a-dir");
            Files.createFile(aFile);

            String result = buildToolsService.validateBuildConfiguration(aFile.toString());

            assertThat(result).contains("\"error\"");
        }
    }
}
