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
package com.pragmatik.buildtools.maven;

import com.pragmatik.buildtools.build.BuildToolsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.pragmatik.buildtools.application.BuildToolsApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = BuildToolsApplication.class)
@DisplayName("Maven application integration tests")
class MavenIntegrationTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private BuildToolsService buildToolsService;

    @Test
    @DisplayName("Spring context loads successfully")
    void contextLoads() {
        assertThat(toolCallbackProvider).isNotNull();
        assertThat(buildToolsService).isNotNull();
    }

    // ──────────────────────────────────────────────
    //  Tool registration verification
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Tool registration")
    class ToolRegistration {

        @Test
        @DisplayName("ToolCallbackProvider resolves get_build_tool_version tool")
        void resolvesGetBuildToolVersionTool() {
            ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
            assertThat(toolCallbacks).isNotNull();
            assertThat(toolCallbacks).isNotEmpty();

            boolean hasVersionTool = Arrays.stream(toolCallbacks).anyMatch(tc -> "get_build_tool_version"
                    .equals(tc.getToolDefinition().name()));
            assertThat(hasVersionTool).isTrue();
        }

        @Test
        @DisplayName("ToolCallbackProvider resolves execute_build_command tool")
        void resolvesExecuteBuildCommandTool() {
            ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
            boolean hasExecTool = Arrays.stream(toolCallbacks).anyMatch(tc -> "execute_build_command"
                    .equals(tc.getToolDefinition().name()));
            assertThat(hasExecTool).isTrue();
        }

        @Test
        @DisplayName("ToolCallbackProvider resolves list_build_tools tool")
        void resolvesListBuildToolsTool() {
            ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
            boolean hasListTool = Arrays.stream(toolCallbacks).anyMatch(tc -> "list_build_tools"
                    .equals(tc.getToolDefinition().name()));
            assertThat(hasListTool).isTrue();
        }

        @Test
        @DisplayName("ToolCallbackProvider resolves detect_build_tool tool (Phase 1)")
        void resolvesDetectBuildToolTool() {
            ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
            boolean hasDetectTool = Arrays.stream(toolCallbacks).anyMatch(tc -> "detect_build_tool"
                    .equals(tc.getToolDefinition().name()));
            assertThat(hasDetectTool).isTrue();
        }

        @Test
        @DisplayName("ToolCallbackProvider resolves check_dependency_version tool (Phase 1)")
        void resolvesCheckDependencyVersionTool() {
            ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
            boolean hasDepTool = Arrays.stream(toolCallbacks).anyMatch(tc -> "check_dependency_version"
                    .equals(tc.getToolDefinition().name()));
            assertThat(hasDepTool).isTrue();
        }
    }

    // ──────────────────────────────────────────────
    //  detect_build_tool integration
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("detect_build_tool integration")
    class DetectBuildTool {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("detects Maven project by pom.xml marker")
        void detectsMavenProject() throws Exception {
            java.nio.file.Files.createFile(tempDir.resolve("pom.xml"));

            String result = buildToolsService.detectBuildTool(tempDir.toString());
            assertThat(result).contains("\"maven\"");
            assertThat(result).contains("\"detected\":true");
            assertThat(result).contains("\"pom.xml\"");
        }

        @Test
        @DisplayName("detects Gradle project by build.gradle marker")
        void detectsGradleProject() throws Exception {
            java.nio.file.Files.createFile(tempDir.resolve("build.gradle"));

            String result = buildToolsService.detectBuildTool(tempDir.toString());
            assertThat(result).contains("\"gradle\"");
            assertThat(result).contains("\"detected\":true");
        }

        @Test
        @DisplayName("detects no build tool for empty directory")
        void detectsNoBuildTool() {
            String result = buildToolsService.detectBuildTool(tempDir.toString());
            assertThat(result).contains("\"toolCount\":0");
        }

        @Test
        @DisplayName("returns error for nonexistent project directory")
        void returnsErrorForNonexistentDir() {
            String result = buildToolsService.detectBuildTool(
                    tempDir.resolve("nonexistent").toString());
            assertThat(result).contains("\"error\"");
        }

        @Test
        @DisplayName("detects hybrid project with multiple markers")
        void detectsHybridProject() throws Exception {
            java.nio.file.Files.createFile(tempDir.resolve("pom.xml"));
            java.nio.file.Files.createFile(tempDir.resolve("build.gradle"));

            String result = buildToolsService.detectBuildTool(tempDir.toString());
            assertThat(result).contains("\"maven\"");
            assertThat(result).contains("\"gradle\"");
            assertThat(result).contains("\"toolCount\":2");
        }
    }

    // ──────────────────────────────────────────────
    //  get_build_tool_version integration
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("get_build_tool_version integration")
    class GetBuildToolVersion {

        @Test
        @DisplayName("get_maven_version returns valid version string")
        void getMavenVersionReturnsValidString() {
            String version = buildToolsService.getBuildToolVersion("maven");
            assertThat(version).isNotNull().isNotEmpty().contains("Apache Maven");
        }

        @Test
        @DisplayName("throws for unknown build tool")
        void throwsForUnknownBuildTool() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> buildToolsService.getBuildToolVersion("bazel"))
                    .withMessageContaining("Unknown build tool");
        }
    }
}
