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
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest
@DisplayName("Maven application integration tests")
class MavenIntegrationTest {

    private static final String MAVEN_HOME = TestUtils.resolveMavenHome();

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private MavenService mavenService;

    @Test
    @DisplayName("Spring context loads successfully")
    void contextLoads() {
        assertThat(toolCallbackProvider).isNotNull();
        assertThat(mavenService).isNotNull();
    }

    @Test
    @DisplayName("ToolCallbackProvider resolves get_maven_version tool")
    void resolvesGetMavenVersionTool() {
        FunctionCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        assertThat(toolCallbacks).isNotNull();
        assertThat(toolCallbacks).isNotEmpty();

        boolean hasVersionTool = Arrays.stream(toolCallbacks)
                .anyMatch(tc -> "get_maven_version".equals(tc.getName()));
        assertThat(hasVersionTool).isTrue();
    }

    @Test
    @DisplayName("ToolCallbackProvider resolves execute_maven_command tool")
    void resolvesExecuteMavenCommandTool() {
        FunctionCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();
        boolean hasExecTool = Arrays.stream(toolCallbacks)
                .anyMatch(tc -> "execute_maven_command".equals(tc.getName()));
        assertThat(hasExecTool).isTrue();
    }

    @Test
    @DisplayName("get_maven_version returns valid version string")
    void getMavenVersionReturnsValidString() {
        String version = mavenService.version();
        assertThat(version)
                .isNotNull()
                .isNotEmpty()
                .contains("Apache Maven");
    }

    @Nested
    @DisplayName("execute_maven_command integration")
    class ExecuteMavenCommand {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("execute mvn --version with valid mavenHome and temp projectDir")
        void executesVersionCommandWithValidInputs() throws Exception {
            java.nio.file.Files.writeString(
                    tempDir.resolve("pom.xml"),
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                    "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "  xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                    "  <modelVersion>4.0.0</modelVersion>\n" +
                    "  <groupId>test</groupId>\n" +
                    "  <artifactId>test</artifactId>\n" +
                    "  <version>1.0</version>\n" +
                    "</project>"
            );

            String result = mavenService.executeCommand(MAVEN_HOME, tempDir.toString(), "mvn --version");
            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("execute with nonexistent projectDir throws")
        void executeWithNonexistentProjectDirThrows() {
            Path nonexistent = tempDir.resolve("nonexistent-project");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> mavenService.executeCommand(
                            MAVEN_HOME, nonexistent.toString(), "clean"))
                    .withMessageContaining("Cannot resolve project directory");
        }

        @Test
        @DisplayName("execute with null projectDir throws")
        void executeWithNullProjectDirThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> mavenService.executeCommand(MAVEN_HOME, null, "clean"))
                    .withMessageContaining("cannot be null");
        }
    }
}
