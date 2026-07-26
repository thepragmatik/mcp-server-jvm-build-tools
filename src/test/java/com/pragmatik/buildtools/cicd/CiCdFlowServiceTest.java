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
package com.pragmatik.buildtools.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CiCdFlowService} — MCP tool surface integration tests.
 */
@DisplayName("CiCdFlowService integration tests")
class CiCdFlowServiceTest {

    private GitHubActionsGenerator generator;
    private CiCdFlowService service;

    @BeforeEach
    void setUp() {
        generator = new GitHubActionsGenerator();
        service = new CiCdFlowService(generator);
    }

    @Nested
    @DisplayName("interpret_ci_flow tool")
    class InterpretCiFlowTests {

        @Test
        @DisplayName("generates push-triggered CI for NL description")
        void generatesPushTriggeredCi() {
            String result =
                    service.interpretCiFlow("Run tests on every push to main", null, null, null, null, null, null);

            assertThat(result).contains("\"pipelineName\":\"CI - Push\"");
            assertThat(result).contains("\"target\":\"github-actions\"");
            assertThat(result).contains("\"config\"");
            assertThat(result).contains("CI - Push");
            assertThat(result).contains("push:");
            assertThat(result).contains("branches: [main]");
            assertThat(result).contains("mvn -B verify");
        }

        @Test
        @DisplayName("generates PR + deploy pipeline for multi-keyword description")
        void generatesPrAndDeployPipeline() {
            String result = service.interpretCiFlow("Build on PR, deploy on tag", null, null, null, null, null, null);

            assertThat(result).contains("CI - Release");
            assertThat(result).contains("release:");
            assertThat(result).contains("types: [published]");
            assertThat(result).contains("deploy");
        }

        @Test
        @DisplayName("detects Maven project from projectDir")
        void detectsMavenProject(@TempDir Path tempDir) throws IOException {
            Files.createFile(tempDir.resolve("pom.xml"));

            String result = service.interpretCiFlow("Build on push", tempDir.toString(), null, null, null, null, null);

            assertThat(result).contains("\"detectedBuildTool\":\"maven\"");
        }

        @Test
        @DisplayName("detects Gradle project from projectDir")
        void detectsGradleProject(@TempDir Path tempDir) throws IOException {
            Files.createFile(tempDir.resolve("build.gradle"));

            String result = service.interpretCiFlow("Build on push", tempDir.toString(), null, null, null, null, null);

            assertThat(result).contains("\"detectedBuildTool\":\"gradle\"");
        }

        @Test
        @DisplayName("detects SBT project from projectDir")
        void detectsSbtProject(@TempDir Path tempDir) throws IOException {
            Files.createFile(tempDir.resolve("build.sbt"));

            String result = service.interpretCiFlow("Build on push", tempDir.toString(), null, null, null, null, null);

            assertThat(result).contains("\"detectedBuildTool\":\"sbt\"");
        }

        @Test
        @DisplayName("generates Gradle commands for Gradle project")
        void generatesGradleCommands(@TempDir Path tempDir) throws IOException {
            Files.createFile(tempDir.resolve("build.gradle"));

            String result = service.interpretCiFlow("Build on push", tempDir.toString(), null, null, null, null, null);

            assertThat(result).contains("./gradlew build");
            assertThat(result).contains("Gradle");
        }

        @Test
        @DisplayName("returns error for empty description")
        void errorsOnEmptyDescription() {
            String result = service.interpretCiFlow("", null, null, null, null, null, null);

            assertThat(result).contains("\"success\":false");
            assertThat(result).contains("CI description is required");
        }

        @Test
        @DisplayName("returns error for blank description")
        void errorsOnBlankDescription() {
            String result = service.interpretCiFlow("   ", null, null, null, null, null, null);

            assertThat(result).contains("\"success\":false");
            assertThat(result).contains("CI description is required");
        }

        @Test
        @DisplayName("returns unknown build tool when project dir doesn't exist")
        void returnsUnknownBuildToolForMissingDir() {
            String result = service.interpretCiFlow("Build on push", "/nonexistent/path", null, null, null, null, null);

            assertThat(result).contains("\"detectedBuildTool\":\"unknown\"");
        }

        @Test
        @DisplayName("includes suggested path in output")
        void includesSuggestedPath() {
            String result = service.interpretCiFlow("Build on push", null, null, null, null, null, null);

            assertThat(result).contains("\"suggestedPath\":\".github/workflows/ci.yml\"");
        }

        @Test
        @DisplayName("includes pipeline summary with triggers")
        void includesPipelineSummary() {
            String result =
                    service.interpretCiFlow("Run tests on push and deploy on tag", null, null, null, null, null, null);

            assertThat(result).contains("\"pipelineSummary\"");
            assertThat(result).contains("\"triggers\"");
            assertThat(result).contains("\"totalSteps\"");
        }
    }

    @Nested
    @DisplayName("validate_ci_flow tool")
    class ValidateCiFlowTests {

        @Test
        @DisplayName("validates well-formed workflow YAML")
        void validatesWellFormedYaml() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n";

            String result = service.validateCiFlow(yaml);

            assertThat(result).contains("\"valid\":true");
        }

        @Test
        @DisplayName("rejects invalid workflow YAML")
        void rejectsInvalidYaml() {
            String yaml = "on:\n" + "  push: [main]\n";

            String result = service.validateCiFlow(yaml);

            assertThat(result).contains("\"valid\":false");
        }

        @Test
        @DisplayName("rejects empty YAML")
        void rejectsEmptyYaml() {
            String result = service.validateCiFlow("");

            assertThat(result).contains("\"valid\":false");
        }

        @Test
        @DisplayName("rejects YAML missing runs-on")
        void rejectsYamlMissingRunsOn() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n";

            String result = service.validateCiFlow(yaml);

            assertThat(result).contains("\"valid\":false");
            assertThat(result).contains("runs-on");
        }
    }

    @Nested
    @DisplayName("PipelineShapeDetector unit tests")
    class PipelineShapeDetectorTests {

        @Test
        @DisplayName("detects CI_PUSH for push keyword")
        void detectsCiPush() {
            assertThat(PipelineShapeDetector.detect("Run tests on every push"))
                    .isEqualTo(PipelineShapeDetector.Shape.CI_PUSH);
        }

        @Test
        @DisplayName("detects CI_PR for pull request keyword")
        void detectsCiPr() {
            assertThat(PipelineShapeDetector.detect("Build on pull request"))
                    .isEqualTo(PipelineShapeDetector.Shape.CI_PR);
        }

        @Test
        @DisplayName("detects CI_RELEASE for deploy/tag keywords")
        void detectsCiRelease() {
            assertThat(PipelineShapeDetector.detect("Build and deploy on release"))
                    .isEqualTo(PipelineShapeDetector.Shape.CI_RELEASE);
        }

        @Test
        @DisplayName("detects CI_FULL for push+PR+release")
        void detectsCiFull() {
            assertThat(PipelineShapeDetector.detect("Build on push, test on PR, deploy on tag"))
                    .isEqualTo(PipelineShapeDetector.Shape.CI_FULL);
        }

        @Test
        @DisplayName("defaults to CI_PUSH for ambiguous descriptions")
        void defaultsToCiPush() {
            assertThat(PipelineShapeDetector.detect("Build project")).isEqualTo(PipelineShapeDetector.Shape.CI_PUSH);
        }

        @Test
        @DisplayName("returns CI_PUSH for null description")
        void handlesNullDescription() {
            assertThat(PipelineShapeDetector.detect(null)).isEqualTo(PipelineShapeDetector.Shape.CI_PUSH);
        }

        @Test
        @DisplayName("builds correct Maven command")
        void mavenBuildCommand() {
            assertThat(PipelineShapeDetector.buildCommand("maven")).isEqualTo("mvn -B verify");
        }

        @Test
        @DisplayName("builds correct Gradle command")
        void gradleBuildCommand() {
            assertThat(PipelineShapeDetector.buildCommand("gradle")).isEqualTo("./gradlew build");
        }

        @Test
        @DisplayName("builds correct SBT command")
        void sbtBuildCommand() {
            assertThat(PipelineShapeDetector.buildCommand("sbt")).isEqualTo("sbt test");
        }

        @Test
        @DisplayName("caches config for Maven")
        void mavenCacheConfig() {
            PipelineShapeDetector.BuildToolCacheConfig config = PipelineShapeDetector.cacheConfig("maven");
            assertThat(config.path()).isEqualTo("~/.m2/repository");
            assertThat(config.key()).contains("hashFiles('**/pom.xml')");
        }

        @Test
        @DisplayName("caches config for Gradle")
        void gradleCacheConfig() {
            PipelineShapeDetector.BuildToolCacheConfig config = PipelineShapeDetector.cacheConfig("gradle");
            assertThat(config.path()).contains("~/.gradle/caches");
            assertThat(config.key()).contains("gradle-wrapper.properties");
        }
    }
}
