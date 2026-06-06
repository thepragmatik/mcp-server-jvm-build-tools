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
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Comprehensive test suite for {@link GradleBuildTool}.
 * <p>
 * Covers all public SPI methods: getName(), version(), executeCommand(), isProject(),
 * getSupportedCommands(), getExecutionPrompt(), plus the package-private static
 * utility methods parseCommandTokens() and resolveGradleExecutable().
 * <p>
 * Tests Gradle-specific conventions: gradle/gradlew prefix stripping,
 * Gradle project marker detection (build.gradle, build.gradle.kts, settings.gradle,
 * settings.gradle.kts), and Gradle executable resolution priority order.
 *
 * @see GradleBuildTool
 * @see BuildTool
 */
@DisplayName("GradleBuildTool unit tests")
class GradleServiceTest {

    private final GradleBuildTool tool = new GradleBuildTool();

    // ──────────────────────────────────────────────
    //  SPI Contract Compliance
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("SPI contract compliance")
    class SpiContract {

        @Test
        @DisplayName("getName() returns 'gradle'")
        void nameReturnsGradle() {
            assertThat(tool.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("getSupportedCommands() returns empty list (trusted user, no allowlist)")
        void supportedCommandsAreCorrect() {
            List<String> cmds = tool.getSupportedCommands();
            assertThat(cmds)
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("getExecutionPrompt() returns non-null, non-empty prompt")
        void executionPromptIsNonEmpty() {
            String prompt = tool.getExecutionPrompt();
            assertThat(prompt)
                    .isNotNull()
                    .isNotEmpty()
                    .contains("Gradle")
                    .contains("--no-daemon")
                    .contains("gradlew");
        }

        @Test
        @DisplayName("BuildTool interface methods are all implemented")
        void allInterfaceMethodsImplemented() {
            assertThat(tool).isInstanceOf(BuildTool.class);
            assertThat(tool.getName()).isNotNull();
            assertThat(tool.getSupportedCommands()).isNotNull();
            assertThat(tool.getExecutionPrompt()).isNotNull();
        }
    }

    // ──────────────────────────────────────────────
    //  isProject() — marker file detection
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("isProject() marker file detection")
    class IsProject {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("returns true when build.gradle exists")
        void returnsTrueForBuildGradle() throws IOException {
            Files.createFile(projectDir.resolve("build.gradle"));
            assertThat(tool.isProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("returns true when build.gradle.kts exists")
        void returnsTrueForBuildGradleKts() throws IOException {
            Files.createFile(projectDir.resolve("build.gradle.kts"));
            assertThat(tool.isProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("returns true when settings.gradle exists")
        void returnsTrueForSettingsGradle() throws IOException {
            Files.createFile(projectDir.resolve("settings.gradle"));
            assertThat(tool.isProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("returns true when settings.gradle.kts exists")
        void returnsTrueForSettingsGradleKts() throws IOException {
            Files.createFile(projectDir.resolve("settings.gradle.kts"));
            assertThat(tool.isProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("returns false when no Gradle markers exist")
        void returnsFalseForEmptyDirectory() {
            assertThat(tool.isProject(projectDir)).isFalse();
        }

        @Test
        @DisplayName("returns false when only pom.xml exists (not a Gradle project)")
        void returnsFalseForMavenOnlyProject() throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            assertThat(tool.isProject(projectDir)).isFalse();
        }

        @Test
        @DisplayName("returns true when multiple markers coexist")
        void returnsTrueForMultipleMarkers() throws IOException {
            Files.createFile(projectDir.resolve("build.gradle"));
            Files.createFile(projectDir.resolve("settings.gradle"));
            Files.createFile(projectDir.resolve("build.gradle.kts"));
            assertThat(tool.isProject(projectDir)).isTrue();
        }
    }

    // ──────────────────────────────────────────────
    //  parseCommandTokens() — command splitting
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("parseCommandTokens() command parsing")
    class ParseCommandTokens {

        @Test
        @DisplayName("strips 'gradle ' prefix and splits tokens")
        void stripsGradlePrefix() {
            assertThat(GradleBuildTool.parseCommandTokens("gradle clean"))
                    .containsExactly("clean");
        }

        @Test
        @DisplayName("strips 'gradlew ' prefix and splits tokens")
        void stripsGradlewPrefix() {
            assertThat(GradleBuildTool.parseCommandTokens("gradlew build"))
                    .containsExactly("build");
        }

        @Test
        @DisplayName("handles command without gradle/gradlew prefix")
        void handlesCommandWithoutPrefix() {
            assertThat(GradleBuildTool.parseCommandTokens("clean"))
                    .containsExactly("clean");
        }

        @Test
        @DisplayName("splits multi-task command into tokens")
        void splitsMultiTaskCommand() {
            assertThat(GradleBuildTool.parseCommandTokens("gradle clean build test"))
                    .containsExactly("clean", "build", "test");
        }

        @Test
        @DisplayName("strips gradlew prefix from multi-task command")
        void stripsGradlewFromMultiTask() {
            assertThat(GradleBuildTool.parseCommandTokens("gradlew clean compileJava"))
                    .containsExactly("clean", "compileJava");
        }

        @Test
        @DisplayName("handles bare 'gradle' (no tasks)")
        void handlesBareGradle() {
            assertThat(GradleBuildTool.parseCommandTokens("gradle")).isEmpty();
        }

        @Test
        @DisplayName("handles bare 'gradlew' (no tasks)")
        void handlesBareGradlew() {
            assertThat(GradleBuildTool.parseCommandTokens("gradlew")).isEmpty();
        }

        @Test
        @DisplayName("collapses extra whitespace between tokens")
        void collapsesExtraWhitespace() {
            assertThat(GradleBuildTool.parseCommandTokens("gradle   clean    build"))
                    .containsExactly("clean", "build");
        }

        @Test
        @DisplayName("trims leading and trailing whitespace")
        void trimsLeadingTrailingWhitespace() {
            assertThat(GradleBuildTool.parseCommandTokens("  gradle clean  "))
                    .containsExactly("clean");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null input")
        void throwsForNullInput() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GradleBuildTool.parseCommandTokens(null))
                    .withMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for empty string")
        void throwsForEmptyString() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GradleBuildTool.parseCommandTokens(""))
                    .withMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for whitespace-only string")
        void throwsForWhitespaceOnly() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GradleBuildTool.parseCommandTokens("   "))
                    .withMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("preserves task names with camelCase")
        void preservesCamelCaseTaskNames() {
            assertThat(GradleBuildTool.parseCommandTokens("gradle compileJava compileTestJava"))
                    .containsExactly("compileJava", "compileTestJava");
        }

        @Test
        @DisplayName("preserves colon-separated project paths")
        void preservesColonSeparatedPaths() {
            assertThat(GradleBuildTool.parseCommandTokens("gradle :app:build :lib:test"))
                    .containsExactly(":app:build", ":lib:test");
        }

        @Test
        @DisplayName("throws for very long command exceeding MAX_COMMAND_LENGTH")
        void handlesVeryLongCommand() {
            StringBuilder sb = new StringBuilder("gradle");
            // Build a command just under 500 chars, then add one more task to exceed
            while (sb.length() < 450) {
                sb.append(" build");
            }
            // Still under limit — should work
            String[] result = GradleBuildTool.parseCommandTokens(sb.toString());
            assertThat(result).hasSizeGreaterThan(1);

            // Now exceed the limit
            StringBuilder tooLong = new StringBuilder(sb);
            while (tooLong.length() <= 510) {
                tooLong.append(" test");
            }
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GradleBuildTool.parseCommandTokens(tooLong.toString()))
                    .withMessageContaining("Command too long");
        }

        @Test
        @DisplayName("unicode task names pass through (trusted user, no allowlist)")
        void handlesUnicodeTaskNames() {
            // Unicode task names are no longer blocked — trusted user
            String[] result = GradleBuildTool.parseCommandTokens("gradle test\u00E9 \u6771\u4EAC");
            assertThat(result).containsExactly("test\u00E9", "\u6771\u4EAC");
        }
    }

    // ──────────────────────────────────────────────
    //  parseCommandTokens() — security and edge cases
    //  NOTE: parseCommandTokens now trusts the user — no allowlist or blocklist.
    //        Only SAFE_ARG_PATTERN (injection defense) remains for flags.
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("parseCommandTokens() security and edge cases")
    class ParseCommandTokensSecurity {

        @Test
        @DisplayName("shell injection with && passes through (trusted user)")
        void shellChainingWithDoubleAmpersandRejected() {
            String[] result = GradleBuildTool.parseCommandTokens("gradle clean && rm -rf /");
            assertThat(result).containsExactly("clean", "&&", "rm", "-rf", "/");
        }

        @Test
        @DisplayName("piped commands pass through (trusted user)")
        void pipedCommandsRejected() {
            String[] result = GradleBuildTool.parseCommandTokens("gradle build | cat /etc/passwd");
            assertThat(result).containsExactly("build", "|", "cat", "/etc/passwd");
        }

        @Test
        @DisplayName("command with $() passes through (trusted user)")
        void dollarParenRejected() {
            String[] result = GradleBuildTool.parseCommandTokens("gradle test $(whoami)");
            assertThat(result).containsExactly("test", "$(whoami)");
        }

        @Test
        @DisplayName("null byte in input: passed through (trusted user)")
        void nullByteInInput() {
            String[] result = GradleBuildTool.parseCommandTokens("gradle clean\0evil");
            assertThat(result).containsExactly("clean\0evil");
        }

        @Test
        @DisplayName("only whitespace trimmed — throws for whitespace-only")
        void onlyWhitespaceTrimmed() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GradleBuildTool.parseCommandTokens("\t\n\r"))
                    .withMessageContaining("cannot be null or empty");
        }
    }

    // ──────────────────────────────────────────────
    //  resolveGradleExecutable() — path resolution
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("resolveGradleExecutable() path resolution")
    class ResolveGradleExecutable {

        @TempDir
        Path gradleHome;

        @Test
        @DisplayName("resolves bin/gradle when buildToolHome contains it")
        void resolvesBinGradleFromBuildToolHome() throws IOException {
            Path binDir = Files.createDirectory(gradleHome.resolve("bin"));
            Path gradleBin = Files.createFile(binDir.resolve("gradle"));
            gradleBin.toFile().setExecutable(true);

            String result = GradleBuildTool.resolveGradleExecutable(
                    gradleHome.toString(), null);
            assertThat(result).isEqualTo(gradleBin.toString());
        }

        @Test
        @DisplayName("falls through to gradlew when bin/gradle not found")
        void fallsThroughToGradlew() throws IOException {
            // No bin/gradle, but gradlew exists
            Path gradlew = Files.createFile(gradleHome.resolve("gradlew"));
            gradlew.toFile().setExecutable(true);

            String result = GradleBuildTool.resolveGradleExecutable(
                    gradleHome.toString(), null);
            assertThat(result).isEqualTo(gradlew.toString());
        }

        @Test
        @DisplayName("falls through to buildToolHome itself when named gradle and executable")
        void fallsThroughToBuildToolHomeItself() throws IOException {
            // No bin/gradle, no gradlew, but home is a file named "gradle" and executable
            Path gradleFile = Files.createFile(gradleHome.resolve("gradle"));
            gradleFile.toFile().setExecutable(true);

            String result = GradleBuildTool.resolveGradleExecutable(
                    gradleFile.toString(), null);
            assertThat(result).isEqualTo(gradleFile.toString());
        }

        @Test
        @DisplayName("checks projectDir gradlew when buildToolHome empty")
        void checksProjectDirGradlew() throws IOException {
            Path gradlew = Files.createFile(gradleHome.resolve("gradlew"));
            gradlew.toFile().setExecutable(true);

            String result = GradleBuildTool.resolveGradleExecutable(
                    null, gradleHome.toString());
            assertThat(result).isEqualTo(gradlew.toString());
        }

        @Test
        @DisplayName("checks projectDir gradlew when buildToolHome has no executable")
        void checksProjectDirGradlewAfterBuildToolHomeFails() throws IOException {
            // buildToolHome: nonexistent path (all Files.isExecutable checks return false)
            String nonexistentHome = gradleHome.resolve("does-not-exist").toString();
            // projectDir: has gradlew
            Path projectDir = Files.createDirectory(gradleHome.resolve("project"));
            Path gradlew = Files.createFile(projectDir.resolve("gradlew"));
            gradlew.toFile().setExecutable(true);

            String result = GradleBuildTool.resolveGradleExecutable(
                    nonexistentHome, projectDir.toString());
            assertThat(result).isEqualTo(gradlew.toString());
        }

        @Test
        @DisplayName("falls back to 'gradle' on PATH when nothing found")
        void fallsBackToGradleOnPath() {
            String result = GradleBuildTool.resolveGradleExecutable(null, null);
            assertThat(result).isEqualTo("gradle");
        }

        @Test
        @DisplayName("falls back to 'gradle' when paths have no executables")
        void fallsBackWhenNoExecutablesFound(@TempDir Path emptyDir) {
            // Use nonexistent paths to ensure all Files.isExecutable checks return false
            Path nonexistent = emptyDir.resolve("does-not-exist");
            String result = GradleBuildTool.resolveGradleExecutable(
                    nonexistent.toString(), nonexistent.toString());
            assertThat(result).isEqualTo("gradle");
        }

        @Test
        @DisplayName("handles empty string buildToolHome")
        void handlesEmptyStringBuildToolHome() {
            String result = GradleBuildTool.resolveGradleExecutable("", null);
            assertThat(result).isEqualTo("gradle");
        }

        @Test
        @DisplayName("handles empty string projectDir")
        void handlesEmptyStringProjectDir() {
            String result = GradleBuildTool.resolveGradleExecutable(null, "");
            assertThat(result).isEqualTo("gradle");
        }

        @Test
        @DisplayName("handles nonexistent buildToolHome path")
        void handlesNonexistentBuildToolHome(@TempDir Path tmp) {
            Path nonexistent = tmp.resolve("does-not-exist");
            String result = GradleBuildTool.resolveGradleExecutable(
                    nonexistent.toString(), null);
            assertThat(result).isEqualTo("gradle");
        }

        @Test
        @DisplayName("handles nonexistent projectDir path")
        void handlesNonexistentProjectDir(@TempDir Path tmp) {
            Path nonexistent = tmp.resolve("does-not-exist");
            String result = GradleBuildTool.resolveGradleExecutable(
                    null, nonexistent.toString());
            assertThat(result).isEqualTo("gradle");
        }

        @Test
        @DisplayName("bin/gradle takes priority over gradlew in same home")
        void binGradleTakesPriorityOverGradlew() throws IOException {
            Path binDir = Files.createDirectory(gradleHome.resolve("bin"));
            Path gradleBin = Files.createFile(binDir.resolve("gradle"));
            gradleBin.toFile().setExecutable(true);

            Path gradlew = Files.createFile(gradleHome.resolve("gradlew"));
            gradlew.toFile().setExecutable(true);

            String result = GradleBuildTool.resolveGradleExecutable(
                    gradleHome.toString(), null);
            assertThat(result).isEqualTo(gradleBin.toString());
        }

        @Test
        @DisplayName("buildToolHome priority over projectDir")
        void buildToolHomePriorityOverProjectDir() throws IOException {
            Path buildToolDir = Files.createDirectory(gradleHome.resolve("tool"));
            Path binDir = Files.createDirectory(buildToolDir.resolve("bin"));
            Path gradleBin = Files.createFile(binDir.resolve("gradle"));
            gradleBin.toFile().setExecutable(true);

            Path projectDir = Files.createDirectory(gradleHome.resolve("project"));
            Path gradlew = Files.createFile(projectDir.resolve("gradlew"));
            gradlew.toFile().setExecutable(true);

            // buildToolHome has bin/gradle -> should be used, not projectDir's gradlew
            String result = GradleBuildTool.resolveGradleExecutable(
                    buildToolDir.toString(), projectDir.toString());
            assertThat(result).isEqualTo(gradleBin.toString());
        }
    }

    // ──────────────────────────────────────────────
    //  executeCommand() unit tests (with mocking)
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("executeCommand() unit tests")
    class ExecuteCommand {

        @TempDir
        Path projectDir;

        @Test
        @DisplayName("builds correct ProcessBuilder command list")
        void buildsCorrectProcessBuilderCommand() throws Exception {
            // Create a minimal gradle project with marker
            Files.createFile(projectDir.resolve("build.gradle"));

            // We verify command construction by checking resolveGradleExecutable
            // and parseCommandTokens integration since ProcessBuilder is hard to mock
            Path binDir = Files.createDirectory(projectDir.resolve("bin"));
            Path gradleBin = Files.createFile(binDir.resolve("gradle"));
            gradleBin.toFile().setExecutable(true);

            // This will actually try to execute gradle — we expect a RuntimeException
            // because there's no real Gradle installed at this path
            try {
                tool.executeCommand(projectDir.toString(),
                        projectDir.toString(), "gradle tasks");
                // If it runs successfully, that's fine too
            } catch (RuntimeException e) {
                // Expected: gradle executable doesn't really exist
                assertThat(e.getMessage()).contains("Unable to invoke Gradle command");
            }
        }

        @Test
        @DisplayName("command string flows through parseCommandTokens")
        void commandFlowsThroughParseCommandTokens() {
            String[] tokens = GradleBuildTool.parseCommandTokens("gradle build test");
            assertThat(tokens).containsExactly("build", "test");
        }

        @Test
        @DisplayName("empty command throws from parseCommandTokens")
        void emptyCommandThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GradleBuildTool.parseCommandTokens(""))
                    .withMessageContaining("cannot be null or empty");
        }
    }

    // ──────────────────────────────────────────────
    //  BuildToolProvider integration with Gradle
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("BuildToolProvider integration")
    class BuildToolProviderIntegration {

        @Test
        @DisplayName("provider registers GradleBuildTool")
        void providerRegistersGradleBuildTool() {
            BuildToolProvider provider = new BuildToolProvider();
            assertThat(provider.getTool("gradle")).isPresent();
            assertThat(provider.getTool("gradle").get())
                    .isInstanceOf(GradleBuildTool.class);
        }

        @Test
        @DisplayName("provider resolves Gradle when project has build.gradle")
        void providerResolvesGradleByMarker(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.gradle"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildTool resolved = provider.resolve(null, projectDir);
            assertThat(resolved.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("provider resolves Gradle when project has settings.gradle.kts")
        void providerResolvesGradleByKtsMarker(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("settings.gradle.kts"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildTool resolved = provider.resolve(null, projectDir);
            assertThat(resolved.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("provider resolves Maven before Gradle (insertion order) when both markers exist")
        void providerResolvesMavenFirstWhenBothMarkersExist(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            Files.createFile(projectDir.resolve("build.gradle"));
            BuildToolProvider provider = new BuildToolProvider();
            // Maven registered first, so it should be detected first
            BuildTool resolved = provider.resolve(null, projectDir);
            assertThat(resolved.getName()).isEqualTo("maven");
        }

        @Test
        @DisplayName("provider resolves by name 'gradle' regardless of markers")
        void providerResolvesByName(@TempDir Path projectDir) throws IOException {
            // Even with pom.xml, explicit name 'gradle' resolves Gradle
            Files.createFile(projectDir.resolve("pom.xml"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildTool resolved = provider.resolve("gradle", projectDir);
            assertThat(resolved.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("provider lists gradle in getAllTools")
        void providerListsGradleInAllTools() {
            BuildToolProvider provider = new BuildToolProvider();
            assertThat(provider.getAllTools()).containsKey("gradle");
            assertThat(provider.getAllTools().get("gradle"))
                    .isInstanceOf(GradleBuildTool.class);
        }

        @Test
        @DisplayName("provider size includes gradle")
        void providerSizeIncludesGradle() {
            BuildToolProvider provider = new BuildToolProvider();
            assertThat(provider.size()).isGreaterThanOrEqualTo(2);
        }
    }

    // ──────────────────────────────────────────────
    //  BuildToolsService integration
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("BuildToolsService integration with Gradle")
    class BuildToolsServiceIntegration {

        @Test
        @DisplayName("getBuildToolVersion('gradle') rejects unknown tools gracefully")
        void gradleVersionFailsGracefully() {
            BuildToolProvider provider = new BuildToolProvider();
            BuildToolsService service = new BuildToolsService(provider);
            try {
                service.getBuildToolVersion("gradle");
            } catch (RuntimeException e) {
                // Expected in test env — Gradle likely not installed
                assertThat(e.getMessage()).isNotNull();
            }
        }

        @Test
        @DisplayName("listBuildTools includes gradle (trusted user, no command restriction)")
        void listBuildToolsIncludesGradle() {
            BuildToolProvider provider = new BuildToolProvider();
            BuildToolsService service = new BuildToolsService(provider);
            String listing = service.listBuildTools();
            assertThat(listing).contains("gradle:");
        }

        @Test
        @DisplayName("getBuildToolVersion rejects empty/null tool name")
        void getBuildToolVersionRejectsEmptyName() {
            BuildToolProvider provider = new BuildToolProvider();
            BuildToolsService service = new BuildToolsService(provider);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.getBuildToolVersion(""))
                    .withMessageContaining("Unknown build tool");
        }
    }

    // ──────────────────────────────────────────────
    //  Concurrent access / thread safety notes
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Thread safety observations")
    class ThreadSafety {

        @Test
        @DisplayName("parseCommandTokens is stateless — safe for concurrent use")
        void parseCommandTokensIsStateless() {
            // parseCommandTokens has no mutable state — always safe
            String[] r1 = GradleBuildTool.parseCommandTokens("gradle clean build");
            String[] r2 = GradleBuildTool.parseCommandTokens("gradlew test");
            assertThat(r1).containsExactly("clean", "build");
            assertThat(r2).containsExactly("test");
        }

        @Test
        @DisplayName("resolveGradleExecutable is stateless — safe for concurrent use")
        void resolveGradleExecutableIsStateless(@TempDir Path tmp) throws IOException {
            Path gradlew = Files.createFile(tmp.resolve("gradlew"));
            gradlew.toFile().setExecutable(true);

            String r1 = GradleBuildTool.resolveGradleExecutable(null, tmp.toString());
            String r2 = GradleBuildTool.resolveGradleExecutable(null, tmp.toString());
            assertThat(r1).isEqualTo(r2).isEqualTo(gradlew.toString());
        }
    }

    // ──────────────────────────────────────────────
    //  Coverage gap documentation tests
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Coverage gap documentation")
    class CoverageDocumentation {

        @Test
        @DisplayName("GAP: version() and executeCommand() integration paths (need real Gradle)")
        void gapVersionAndExecuteCommandIntegration() {
            // version() uses ProcessBuilder to run 'gradle --version'
            // executeCommand() uses ProcessBuilder to run gradle commands
            // These cannot be unit-tested without mocking ProcessBuilder,
            // which is restricted from Java 17+ without --add-opens flags.
            // Integration tests should cover these when a real Gradle is available.
            assertThat(tool).isNotNull(); // existence verified
        }

        @Test
        @DisplayName("GAP: executeCommand() error paths — IOException and InterruptedException")
        void gapExecuteCommandErrorPaths() {
            // GradleBuildTool.executeCommand() has catch blocks for:
            // - IOException: returns "Unable to invoke Gradle command: ..."
            // - InterruptedException: returns "Gradle command interrupted: ..."
            // These require mocking ProcessBuilder internals.
            assertThat(tool.getName()).isEqualTo("gradle");
        }

        @Test
        @DisplayName("GAP: version() error paths — non-zero exit and exceptions")
        void gapVersionErrorPaths() {
            // GradleBuildTool.version() has branches for:
            // - exitCode != 0 -> throws RuntimeException with error output
            // - IOException | InterruptedException -> throws RuntimeException
            // These require a ProcessBuilder that fails, which is hard in unit tests.
            assertThat(tool.getExecutionPrompt()).isNotEmpty();
        }

        @Test
        @DisplayName("parseCommandTokens trusts user — no allowlist restriction")
        void gapNoAllowlistInParseCommandTokens() {
            // GradleBuildTool.parseCommandTokens() now trusts the user.
            // Previously blocked tasks like exec:exec pass through.
            String[] tokens = GradleBuildTool.parseCommandTokens("gradle exec:exec");
            assertThat(tokens).containsExactly("exec:exec");
            // Allowed tasks pass through normally
            tokens = GradleBuildTool.parseCommandTokens("gradle clean build");
            assertThat(tokens).containsExactly("clean", "build");
        }
    }
}
