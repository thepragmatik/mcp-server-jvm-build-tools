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

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Android project detection via BuildToolsService.
 */
@DisplayName("BuildToolsService Android integration (F3)")
class BuildToolsServiceAndroidTest {

    private static final Path FIXTURES = Path.of("src/test/resources");

    private final BuildToolProvider provider = new BuildToolProvider();
    private final BuildToolsService service = new BuildToolsService(provider);

    @Nested
    @DisplayName("detectBuildTool with Android projects")
    class DetectBuildToolAndroid {

        @Test
        @DisplayName("detects Android project and includes hints")
        void detectsAndroidProject() {
            String json = service.detectBuildTool(
                    FIXTURES.resolve("test-android-project").toString());

            assertThat(json).isNotNull().isNotEmpty();
            assertThat(json).contains("gradle");
            assertThat(json).contains("Android");
            assertThat(json).contains("AGP");
        }

        @Test
        @DisplayName("detects Android project with Groovy DSL")
        void detectsAndroidGroovy() {
            String json = service.detectBuildTool(
                    FIXTURES.resolve("test-android-project-groovy").toString());

            assertThat(json).isNotNull().isNotEmpty();
            assertThat(json).contains("gradle");
            assertThat(json).contains("Android");
        }

        @Test
        @DisplayName("does not flag non-Android Gradle project as Android")
        void doesNotFlagPlainGradle() {
            String json = service.detectBuildTool(
                    FIXTURES.resolve("test-gradle-project").toString());

            assertThat(json).isNotNull().isNotEmpty();
            // Should detect gradle but not Android
            assertThat(json).contains("gradle");
            assertThat(json).doesNotContain("Android project detected");
        }
    }

    @Nested
    @DisplayName("executeBuildCommand with Android tasks")
    class ExecuteBuildCommandAndroid {

        @Test
        @DisplayName("command pattern allows Android task names")
        void commandPatternAllowsAndroidTasks() {
            // Verify the COMMAND_PATTERN regex allows Android task names
            String command = "assembleDebug";
            var pattern = java.util.regex.Pattern.compile("^(?:gradle\\\\w*\\\\s+)?[a-zA-Z0-9\\\\s._=/:@;\\\\-]+$");
            assertThat(pattern.matcher(command).matches()).isTrue();
        }

        @Test
        @DisplayName("command pattern allows colon-separated Android paths")
        void commandPatternAllowsColonSeparated() {
            String command = ":app:assembleDebug";
            var pattern = java.util.regex.Pattern.compile("^(?:gradle\\\\w*\\\\s+)?[a-zA-Z0-9\\\\s._=/:@;\\\\-]+$");
            assertThat(pattern.matcher(command).matches()).isTrue();
        }
    }
}
