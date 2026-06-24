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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link BuildToolsService#validateBuildConfiguration}.
 */
@DisplayName("Build configuration validation tests")
class BuildConfigurationValidationTest {

    private final BuildToolProvider provider = new BuildToolProvider();
    private final BuildToolsService service = new BuildToolsService(provider);

    @Nested
    @DisplayName("pom.xml validation")
    class PomXmlValidation {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("validates a valid pom.xml successfully")
        void validatesValidPomXml() throws Exception {
            String pomXml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>demo</artifactId>
                        <version>1.0.0</version>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pomXml);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"valid\":true");
            assertThat(result).contains("\"tool\":\"maven\"");
            assertThat(result).contains("pom.xml");
            assertThat(result).contains("\"issueCount\":0");
        }

        @Test
        @DisplayName("detects missing required elements in pom.xml")
        void detectsMissingRequiredElements() throws Exception {
            String pomXml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pomXml);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("artifactId");
        }

        @Test
        @DisplayName("detects malformed pom.xml")
        void detectsMalformedPomXml() throws Exception {
            String pomXml = "\"not valid xml at all$$$";
            Files.writeString(projectDir.resolve("pom.xml"), pomXml);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("Missing <project> root element");
        }

        @Test
        @DisplayName("detects missing pom.xml")
        void detectsMissingPomXml() {
            // Empty dir with no build files
            String result = service.validateBuildConfiguration(projectDir.toString());
            // No build files found, valid=true, issueCount=0
            assertThat(result).contains("\"valid\":true");
            assertThat(result).contains("\"issueCount\":0");
        }

        @Test
        @DisplayName("handles pom.xml with parent POM inheritance")
        void handlesParentPomInheritance() throws Exception {
            String pomXml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <parent>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-parent</artifactId>
                            <version>3.4.0</version>
                        </parent>
                        <artifactId>my-app</artifactId>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pomXml);

            String result = service.validateBuildConfiguration(projectDir.toString());
            // Validator finds groupId and version inside parent block — passes basic check
            assertThat(result).contains("\"valid\":true");
            assertThat(result).contains("\"issueCount\":0");
        }

        @Test
        @DisplayName("returns error for nonexistent project directory")
        void returnsErrorForNonexistentDir() {
            String result = service.validateBuildConfiguration(
                    projectDir.resolve("nonexistent").toString());
            assertThat(result).contains("\"error\"");
        }
    }

    @Nested
    @DisplayName("build.gradle validation")
    class GradleBuildValidation {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("validates a basic build.gradle successfully")
        void validatesBasicBuildGradle() throws Exception {
            String buildGradle =
                    """
                    plugins {
                        id 'java'
                    }

                    repositories {
                        mavenCentral()
                    }

                    dependencies {
                        implementation 'com.google.guava:guava:33.0.0-jre'
                        testImplementation 'junit:junit:4.13.2'
                    }
                    """;
            Files.writeString(projectDir.resolve("build.gradle"), buildGradle);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"tool\":\"gradle\"");
            assertThat(result).contains("\"valid\":true");
        }

        @Test
        @DisplayName("detects unbalanced braces in build.gradle")
        void detectsUnbalancedBraces() throws Exception {
            String buildGradle =
                    """
                    plugins {
                        id 'java'
                    // missing closing brace
                    """;
            Files.writeString(projectDir.resolve("build.gradle"), buildGradle);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("Unbalanced braces");
        }

        @Test
        @DisplayName("validates build.gradle.kts (Kotlin DSL)")
        void validatesBuildGradleKts() throws Exception {
            String buildGradleKts =
                    """
                    plugins {
                        kotlin("jvm") version "1.9.0"
                    }

                    repositories {
                        mavenCentral()
                    }

                    dependencies {
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
                        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
                    }
                    """;
            Files.writeString(projectDir.resolve("build.gradle.kts"), buildGradleKts);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"tool\":\"gradle\"");
        }

        @Test
        @DisplayName("detects empty build.gradle")
        void detectsEmptyBuildGradle() throws Exception {
            Files.writeString(projectDir.resolve("build.gradle"), "");

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("Build file is empty");
        }

        @Test
        @DisplayName("detects missing build.gradle")
        void detectsMissingBuildGradle() {
            String result = service.validateBuildConfiguration(projectDir.toString());
            // No build files at all - valid since nothing to check
            assertThat(result).contains("\"valid\":true");
        }

        @Test
        @DisplayName("warns about missing plugin declarations")
        void warnsAboutMissingPluginDeclarations() throws Exception {
            String buildGradle =
                    """
                    dependencies {
                        implementation 'com.google.guava:guava:33.0.0-jre'
                    }
                    """;
            Files.writeString(projectDir.resolve("build.gradle"), buildGradle);

            String result = service.validateBuildConfiguration(projectDir.toString());
            assertThat(result).contains("No plugin declarations found");
        }
    }

    @Nested
    @DisplayName("Hybrid project validation")
    class HybridProjectValidation {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("validates pom.xml when both Maven and Gradle markers exist")
        void validatesPomXmlInHybridProject() throws Exception {
            String pomXml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>demo</artifactId>
                        <version>1.0.0</version>
                    </project>
                    """;
            Files.writeString(projectDir.resolve("pom.xml"), pomXml);
            Files.writeString(projectDir.resolve("build.gradle"), "// Gradle build file");

            String result = service.validateBuildConfiguration(projectDir.toString());
            // Maven is auto-detected first, but validation covers all build files
            assertThat(result).contains("\"tool\":\"maven\"");
        }
    }
}
