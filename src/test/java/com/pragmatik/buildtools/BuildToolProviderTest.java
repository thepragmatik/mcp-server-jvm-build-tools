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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("BuildToolProvider unit tests")
class BuildToolProviderTest {

    private final BuildToolProvider provider = new BuildToolProvider();

    @Nested
    @DisplayName("default registration")
    class DefaultRegistration {

        @Test
        @DisplayName("registers Maven, Gradle, and SBT by default")
        void registersAllThreeTools() {
            assertThat(provider.size()).isEqualTo(3);
            assertThat(provider.getTool("maven")).isPresent();
            assertThat(provider.getTool("gradle")).isPresent();
            assertThat(provider.getTool("sbt")).isPresent();
        }

        @Test
        @DisplayName("default tools are correct types")
        void defaultToolsAreCorrectTypes() {
            assertThat(provider.getTool("maven").get()).isInstanceOf(MavenBuildTool.class);
            assertThat(provider.getTool("gradle").get()).isInstanceOf(GradleBuildTool.class);
            assertThat(provider.getTool("sbt").get()).isInstanceOf(SbtBuildTool.class);
        }
    }

    @Nested
    @DisplayName("getTool() name-based lookup")
    class GetTool {

        @Test
        @DisplayName("returns tool by lowercase name")
        void returnsToolByLowercaseName() {
            assertThat(provider.getTool("maven")).isPresent();
            assertThat(provider.getTool("maven").get().getName()).isEqualTo("maven");
        }

        @Test
        @DisplayName("case-insensitive lookup")
        void caseInsensitiveLookup() {
            assertThat(provider.getTool("MAVEN")).isPresent();
            assertThat(provider.getTool("Maven")).isPresent();
            assertThat(provider.getTool("Gradle")).isPresent();
            assertThat(provider.getTool("SBT")).isPresent();
        }

        @Test
        @DisplayName("returns empty for unknown name")
        void returnsEmptyForUnknownName() {
            assertThat(provider.getTool("bazel")).isEmpty();
            assertThat(provider.getTool("ant")).isEmpty();
        }

        @Test
        @DisplayName("returns empty for null name")
        void returnsEmptyForNullName() {
            assertThat(provider.getTool(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for blank name")
        void returnsEmptyForBlankName() {
            assertThat(provider.getTool("")).isEmpty();
            assertThat(provider.getTool("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolve() tool resolution")
    class Resolve {

        @Test
        @DisplayName("resolves by explicit name")
        void resolveByExplicitName() {
            BuildTool tool = provider.resolve("gradle", null);
            assertThat(tool.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("projectDir ignored when name provided")
        void projectDirIgnoredWhenNameProvided(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            BuildTool tool = provider.resolve("gradle", projectDir);
            assertThat(tool.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("rejects unknown tool name")
        void rejectsUnknownTool() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> provider.resolve("bazel", null))
                    .withMessageContaining("Unknown build tool")
                    .withMessageContaining("bazel");
        }

        @Test
        @DisplayName("message lists registered tools when unknown")
        void messageListsRegisteredTools() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> provider.resolve("unknown", null))
                    .withMessageContaining("maven")
                    .withMessageContaining("gradle")
                    .withMessageContaining("sbt");
        }

        @Test
        @DisplayName("auto-detects Maven when pom.xml present")
        void autoDetectsMaven(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            BuildTool tool = provider.resolve(null, projectDir);
            assertThat(tool.getName()).isEqualTo("maven");
        }

        @Test
        @DisplayName("auto-detects Gradle when build.gradle present")
        void autoDetectsGradle(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.gradle"));
            BuildTool tool = provider.resolve(null, projectDir);
            assertThat(tool.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("auto-detects SBT when build.sbt present")
        void autoDetectsSbt(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));
            BuildTool tool = provider.resolve(null, projectDir);
            assertThat(tool.getName()).isEqualTo("sbt");
        }

        @Test
        @DisplayName("Maven takes priority over Gradle in auto-detect")
        void mavenPriorityOverGradle(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            Files.createFile(projectDir.resolve("build.gradle"));
            BuildTool tool = provider.resolve(null, projectDir);
            assertThat(tool.getName()).isEqualTo("maven");
        }

        @Test
        @DisplayName("falls back to Maven when no markers found")
        void fallbackToMavenWhenNoMarkers(@TempDir Path projectDir) {
            BuildTool tool = provider.resolve(null, projectDir);
            assertThat(tool.getName()).isEqualTo("maven");
        }

        @Test
        @DisplayName("falls back to Maven when projectDir is null")
        void fallbackToMavenWhenProjectDirNull() {
            BuildTool tool = provider.resolve(null, null);
            assertThat(tool.getName()).isEqualTo("maven");
        }
    }

    @Nested
    @DisplayName("register() custom tool registration")
    class Register {

        @Test
        @DisplayName("registers a new build tool")
        void registersNewTool() {
            BuildTool bazel = new StubBuildTool("bazel");
            provider.register(bazel);
            assertThat(provider.getTool("bazel")).isPresent();
            assertThat(provider.getTool("bazel").get()).isSameAs(bazel);
            assertThat(provider.size()).isEqualTo(4);
        }

        @Test
        @DisplayName("overwrites existing tool with same name")
        void overwritesExistingTool() {
            BuildTool customMaven = new StubBuildTool("maven");
            provider.register(customMaven);
            assertThat(provider.getTool("maven").get()).isSameAs(customMaven);
            assertThat(provider.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("new tool appears in auto-detect order after defaults")
        void newToolInAutoDetectOrder(@TempDir Path projectDir) throws IOException {
            BuildTool custom = new StubBuildTool("custom") {
                @Override
                public boolean isProject(Path dir) {
                    return Files.exists(dir.resolve("marker.txt"));
                }
            };
            provider.register(custom);

            Files.createFile(projectDir.resolve("marker.txt"));
            Files.createFile(projectDir.resolve("pom.xml"));
            BuildTool resolved = provider.resolve(null, projectDir);
            assertThat(resolved.getName()).isEqualTo("maven");
        }

        @Test
        @DisplayName("registered tools in getAllTools()")
        void registeredToolsInGetAll() {
            BuildTool bazel = new StubBuildTool("bazel");
            provider.register(bazel);
            Map<String, BuildTool> all = provider.getAllTools();
            assertThat(all).containsKey("bazel");
            assertThat(all.get("bazel")).isSameAs(bazel);
        }
    }

    @Nested
    @DisplayName("getAllTools() and size()")
    class GetAllToolsAndSize {

        @Test
        @DisplayName("returns all three default tools")
        void returnsAllThreeTools() {
            Map<String, BuildTool> all = provider.getAllTools();
            assertThat(all).hasSize(3)
                    .containsKeys("maven", "gradle", "sbt");
        }

        @Test
        @DisplayName("returns unmodifiable map")
        void returnsUnmodifiableMap() {
            Map<String, BuildTool> all = provider.getAllTools();
            assertThat(all).isUnmodifiable();
        }

        @Test
        @DisplayName("size matches getAllTools().size()")
        void sizeMatchesGetAllToolsSize() {
            assertThat(provider.size()).isEqualTo(provider.getAllTools().size());
        }

        @Test
        @DisplayName("size increases after register()")
        void sizeIncreasesAfterRegister() {
            int before = provider.size();
            provider.register(new StubBuildTool("custom"));
            assertThat(provider.size()).isEqualTo(before + 1);
        }
    }

    static class StubBuildTool implements BuildTool {
        private final String name;

        StubBuildTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String version() {
            return "1.0.0-stub";
        }

        @Override
        public String executeCommand(String buildToolHome, String projectDir, String command) {
            return "stub output";
        }

        @Override
        public boolean isProject(Path projectDir) {
            return false;
        }

        @Override
        public java.util.List<String> getSupportedCommands() {
            return java.util.List.of("build", "test");
        }

        @Override
        public String getExecutionPrompt() {
            return "Stub execution prompt";
        }
    }
}
