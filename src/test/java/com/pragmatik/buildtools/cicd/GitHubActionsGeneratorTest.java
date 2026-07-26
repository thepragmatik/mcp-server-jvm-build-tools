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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GitHubActionsGenerator} — YAML generation and validation.
 */
@DisplayName("GitHubActionsGenerator unit tests")
class GitHubActionsGeneratorTest {

    private final GitHubActionsGenerator generator = new GitHubActionsGenerator();

    @Nested
    @DisplayName("YAML generation")
    class GenerationTests {

        @Test
        @DisplayName("generates valid YAML for push-triggered CI (Maven)")
        void generatesPushTriggerCiMaven() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Push")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .strategy(CiCdMatrixStrategy.java("21"))
                            .steps(List.of(
                                    CiCdStep.checkout(),
                                    CiCdStep.setupJava("21"),
                                    CiCdStep.run("Build with Maven", "mvn -B verify")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("CI - Push");
            assertThat(yaml).contains("on:");
            assertThat(yaml).contains("push:");
            assertThat(yaml).contains("branches: [main]");
            assertThat(yaml).contains("runs-on: ubuntu-latest");
            assertThat(yaml).contains("actions/checkout@v4");
            assertThat(yaml).contains("actions/setup-java@v4");
            assertThat(yaml).contains("mvn -B verify");
        }

        @Test
        @DisplayName("generates PR trigger workflow")
        void generatesPrTrigger() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Pull Request")
                    .target("github-actions")
                    .detectedBuildTool("gradle")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .pullRequest(CiCdPrTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .steps(List.of(CiCdStep.checkout(), CiCdStep.run("Build with Gradle", "./gradlew build")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("pull_request:");
            assertThat(yaml).contains("./gradlew build");
        }

        @Test
        @DisplayName("generates release-triggered workflow with deploy job")
        void generatesReleaseWorkflow() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Release")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .pullRequest(CiCdPrTrigger.of(List.of("main")))
                            .release(CiCdReleaseTrigger.published())
                            .build())
                    .jobs(List.of(
                            CiCdJob.builder()
                                    .name("Build")
                                    .id("build")
                                    .runsOn("ubuntu-latest")
                                    .steps(List.of(CiCdStep.run("Build", "mvn -B verify")))
                                    .build(),
                            CiCdJob.builder()
                                    .name("Deploy")
                                    .id("deploy")
                                    .runsOn("ubuntu-latest")
                                    .needs(List.of("build"))
                                    .condition("startsWith(github.ref, 'refs/tags/v')")
                                    .steps(List.of(CiCdStep.run("Deploy", "./gradlew publish")))
                                    .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("release:");
            assertThat(yaml).contains("types: [published]");
            assertThat(yaml).contains("needs: [build]");
            assertThat(yaml).contains("startsWith(github.ref,");
            assertThat(yaml).contains("refs/tags/v");
        }

        @Test
        @DisplayName("generates Maven-specific build commands")
        void generatesMavenCommands() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Maven")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .steps(List.of(
                                    CiCdStep.checkout(),
                                    CiCdStep.setupJava("21"),
                                    CiCdStep.cache(
                                            "Cache Maven dependencies",
                                            "~/.m2/repository",
                                            "maven-${{ hashFiles('**/pom.xml') }}",
                                            "maven-"),
                                    CiCdStep.run("Build with Maven", "mvn -B verify")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("mvn -B verify");
            assertThat(yaml).contains("~/.m2/repository");
            assertThat(yaml).contains("hashFiles");
        }

        @Test
        @DisplayName("generates Gradle-specific build commands")
        void generatesGradleCommands() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Gradle")
                    .target("github-actions")
                    .detectedBuildTool("gradle")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .steps(List.of(
                                    CiCdStep.checkout(),
                                    CiCdStep.setupJava("21"),
                                    CiCdStep.cache(
                                            "Cache Gradle dependencies",
                                            "~/.gradle/caches\n          ~/.gradle/wrapper",
                                            "gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}",
                                            "gradle-"),
                                    CiCdStep.run("Build with Gradle", "./gradlew build")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("./gradlew build");
            assertThat(yaml).contains("~/.gradle/caches");
            assertThat(yaml).contains("gradle-wrapper.properties");
        }

        @Test
        @DisplayName("generates SBT-specific build commands")
        void generatesSbtCommands() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - SBT")
                    .target("github-actions")
                    .detectedBuildTool("sbt")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .steps(List.of(
                                    CiCdStep.checkout(),
                                    CiCdStep.setupJava("21"),
                                    CiCdStep.cache(
                                            "Cache SBT dependencies",
                                            "~/.cache/coursier\n          ~/.sbt",
                                            "sbt-${{ hashFiles('build.sbt', 'project/**') }}",
                                            "sbt-"),
                                    CiCdStep.run("Build with SBT", "sbt test")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("sbt test");
            assertThat(yaml).contains("~/.cache/coursier");
            assertThat(yaml).contains("hashFiles");
        }

        @Test
        @DisplayName("generates JDK matrix strategy")
        void generatesJdkMatrix() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Matrix")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .strategy(CiCdMatrixStrategy.java("17", "21", "23"))
                            .steps(List.of(CiCdStep.run("Build", "mvn -B verify")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("strategy:");
            assertThat(yaml).contains("matrix:");
            assertThat(yaml).contains("java: [17, 21, 23]");
        }

        @Test
        @DisplayName("generates env vars at pipeline and job level")
        void generatesEnvVars() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Env")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .env(Map.of("JAVA_HOME", "/usr/lib/jvm/java-21"))
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .env(Map.of("MY_VAR", "value"))
                            .steps(List.of(CiCdStep.run("Build", "mvn -B verify")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("JAVA_HOME: /usr/lib/jvm/java-21");
            assertThat(yaml).contains("MY_VAR: value");
        }

        @Test
        @DisplayName("generates workflow dispatch trigger")
        void generatesWorkflowDispatch() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Dispatch")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .workflowDispatch(true)
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .steps(List.of(CiCdStep.run("Build", "mvn -B verify")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("workflow_dispatch:");
        }

        @Test
        @DisplayName("generates permissions block")
        void generatesPermissions() {
            CiCdPipeline pipeline = CiCdPipeline.builder()
                    .name("CI - Permissions")
                    .target("github-actions")
                    .detectedBuildTool("maven")
                    .permissions(CiCdPermissions.readAll())
                    .on(CiCdTrigger.builder()
                            .push(CiCdPushTrigger.of(List.of("main")))
                            .build())
                    .jobs(List.of(CiCdJob.builder()
                            .name("Build")
                            .id("build")
                            .runsOn("ubuntu-latest")
                            .steps(List.of(CiCdStep.run("Build", "mvn -B verify")))
                            .build()))
                    .build();

            String yaml = generator.generate(pipeline);

            assertThat(yaml).contains("permissions:");
            assertThat(yaml).contains("contents: read");
        }
    }

    @Nested
    @DisplayName("YAML validation")
    class ValidationTests {

        @Test
        @DisplayName("validates well-formed YAML")
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

            CiCdTarget.ValidationResult result = generator.validate(yaml);

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("rejects YAML without name")
        void rejectsYamlWithoutName() {
            String yaml = "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n";

            CiCdTarget.ValidationResult result = generator.validate(yaml);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("name");
        }

        @Test
        @DisplayName("rejects YAML without runs-on")
        void rejectsYamlWithoutRunsOn() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n";

            CiCdTarget.ValidationResult result = generator.validate(yaml);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("runs-on");
        }

        @Test
        @DisplayName("rejects YAML without steps")
        void rejectsYamlWithoutSteps() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n";

            CiCdTarget.ValidationResult result = generator.validate(yaml);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("steps");
        }

        @Test
        @DisplayName("rejects empty YAML")
        void rejectsEmptyYaml() {
            CiCdTarget.ValidationResult result = generator.validate("");

            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("empty");
        }

        @Test
        @DisplayName("rejects null YAML")
        void rejectsNullYaml() {
            CiCdTarget.ValidationResult result = generator.validate(null);

            assertThat(result.valid()).isFalse();
        }
    }
}
