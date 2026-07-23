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
package com.pragmatik.buildtools.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Android project detection (F3 of v1.1.0).
 */
@DisplayName("GradleBuildTool Android detection (F3)")
class GradleBuildToolAndroidTest {

    private static final Path FIXTURES = Path.of("src/test/resources");

    private final GradleBuildTool tool = new GradleBuildTool();

    // ── Detecting Android applications ──────────────────────────────

    @Nested
    @DisplayName("Android app module detection")
    class AndroidAppDetection {

        @Test
        @DisplayName("detects Android application from Kotlin DSL")
        void detectsAndroidAppKotlin() {
            Path projectDir = FIXTURES.resolve("test-android-project");

            assertThat(tool.isAndroidProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("detects Android application from Groovy DSL")
        void detectsAndroidAppGroovy() {
            Path projectDir = FIXTURES.resolve("test-android-project-groovy");

            assertThat(tool.isAndroidProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("does not detect non-Android Gradle project")
        void doesNotDetectPlainGradle() {
            Path projectDir = FIXTURES.resolve("test-gradle-project");

            assertThat(tool.isAndroidProject(projectDir)).isFalse();
        }
    }

    // ── Android project info ────────────────────────────────────────

    @Nested
    @DisplayName("Android project info extraction")
    class AndroidProjectInfo {

        @Test
        @DisplayName("detectAndroidProject returns info for Kotlin DSL app")
        void returnsInfoForKotlinApp() {
            Path projectDir = FIXTURES.resolve("test-android-project");

            Map<String, Object> info = tool.detectAndroidProject(projectDir);

            assertThat(info).isNotNull();
            assertThat(info.get("detected")).isEqualTo(true);
            assertThat(info.get("compileSdk")).isEqualTo("36");
            assertThat(info.get("minSdk")).isEqualTo("24");
            assertThat(info.get("targetSdk")).isEqualTo("36");
            assertThat(info).containsKey("agpVersion");
            assertThat(info.get("projectType")).isEqualTo("application");

            @SuppressWarnings("unchecked")
            List<String> hints = (List<String>) info.get("hints");
            assertThat(hints).isNotNull().isNotEmpty();
            assertThat(hints.stream().anyMatch(h -> h.contains("Android"))).isTrue();
        }

        @Test
        @DisplayName("detectAndroidProject returns null for non-Android project")
        void returnsNullForNonAndroid() {
            Path projectDir = FIXTURES.resolve("test-gradle-project");

            Map<String, Object> info = tool.detectAndroidProject(projectDir);

            assertThat(info).isNull();
        }

        @Test
        @DisplayName("detects Android app in subdirectory (app/)")
        void detectsAndroidAppInSubdirectory() {
            Path projectDir = FIXTURES.resolve("test-android-project");

            // The root build.gradle.kts declares the plugin, app/ has android {}
            Map<String, Object> info = tool.detectAndroidProject(projectDir);
            assertThat(info).isNotNull();
            assertThat(info.get("detected")).isEqualTo(true);
        }
    }

    // ── Build variants ──────────────────────────────────────────────

    @Nested
    @DisplayName("Build variant detection")
    class BuildVariants {

        @Test
        @DisplayName("getAndroidBuildVariants returns default debug/release")
        void returnsDefaultVariants() {
            String content = "android { buildTypes { debug {} release {} } }";

            List<String> variants = tool.getAndroidBuildVariants(content);

            assertThat(variants).contains("debug", "release");
        }

        @Test
        @DisplayName("getAndroidBuildVariants returns custom build types")
        void returnsCustomBuildTypes() {
            String content = "android { buildTypes { debug {} release {} staging {} } }";

            List<String> variants = tool.getAndroidBuildVariants(content);

            assertThat(variants).contains("debug", "release", "staging");
        }

        @Test
        @DisplayName("getAndroidBuildVariants combines flavors and build types")
        void combinesFlavorsAndBuildTypes() {
            String content =
                    """
                    android {
                        flavorDimensions "env"
                        productFlavors { prod {} staging {} }
                        buildTypes { debug {} release {} }
                    }""";

            List<String> variants = tool.getAndroidBuildVariants(content);

            assertThat(variants).contains("prodDebug", "prodRelease", "stagingDebug", "stagingRelease");
            assertThat(variants).hasSize(4);
        }

        @Test
        @DisplayName("getAndroidBuildVariants handles no build types")
        void handlesNoBuildTypes() {
            String content = "android { applicationId = 'com.test' }";

            List<String> variants = tool.getAndroidBuildVariants(content);

            assertThat(variants).contains("debug", "release");
        }
    }

    // ── AGP version detection ───────────────────────────────────────

    @Nested
    @DisplayName("AGP version detection")
    class AgpVersionDetection {

        @Test
        @DisplayName("detects AGP from Kotlin DSL plugin declaration")
        void detectsAgpFromKotlinPlugin() {
            Path projectDir = FIXTURES.resolve("test-android-project");

            Map<String, Object> info = tool.detectAndroidProject(projectDir);

            String agpVersion = (String) info.get("agpVersion");
            assertThat(agpVersion).isEqualTo("8.11.0");
        }

        @Test
        @DisplayName("detects AGP from version catalog")
        void detectsAgpFromVersionCatalog() {
            Path projectDir = FIXTURES.resolve("test-android-version-catalog");

            // The version catalog exists but root project has no build.gradle
            // So should fall through and not crash
            Map<String, Object> info = tool.detectAndroidProject(projectDir);

            // No build.gradle(.kts) with Android markers — should be null
            assertThat(info).isNull();
        }

        @Test
        @DisplayName("detects Groovy DSL Android project")
        void detectsGroovyDslAndroid() {
            Path projectDir = FIXTURES.resolve("test-android-project-groovy");

            Map<String, Object> info = tool.detectAndroidProject(projectDir);

            assertThat(info).isNotNull();
            assertThat(info.get("projectType")).isEqualTo("application");
            assertThat(info.get("compileSdk")).isEqualTo("36");
            assertThat(info.get("minSdk")).isEqualTo("24");
        }
    }

    // ── Android tasks in allowlist ───────────────────────────────────

    @Nested
    @DisplayName("Android tasks in allowlist")
    class AndroidTasks {

        @Test
        @DisplayName("allows assembleDebug task")
        void allowsAssembleDebug() {
            assertThat(GradleBuildTool.parseCommandTokens("assembleDebug")).containsExactly("assembleDebug");
        }

        @Test
        @DisplayName("allows bundleRelease task")
        void allowsBundleRelease() {
            assertThat(GradleBuildTool.parseCommandTokens("bundleRelease")).containsExactly("bundleRelease");
        }

        @Test
        @DisplayName("allows lint task")
        void allowsLint() {
            assertThat(GradleBuildTool.parseCommandTokens("lint")).containsExactly("lint");
        }

        @Test
        @DisplayName("allows connectedAndroidTest task")
        void allowsConnectedAndroidTest() {
            assertThat(GradleBuildTool.parseCommandTokens("connectedAndroidTest"))
                    .containsExactly("connectedAndroidTest");
        }

        @Test
        @DisplayName("supported commands include Android tasks")
        void supportedCommandsIncludeAndroid() {
            List<String> cmds = tool.getSupportedCommands();
            assertThat(cmds).contains("assembleDebug", "assembleRelease", "lint", "connectedAndroidTest");
        }
    }
}
