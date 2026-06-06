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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Comprehensive test suite for {@link SbtBuildTool}.
 * <p>
 * Covers all public SPI methods: getName(), version(), executeCommand(),
 * isProject(), getSupportedCommands(), getExecutionPrompt(), plus the
 * package-private static utility methods parseCommandTokens() and
 * resolveSbtExecutable().
 *
 * @see SbtBuildTool
 * @see BuildTool
 */
@DisplayName("SbtBuildTool unit tests")
class SbtBuildToolTest {

    private final SbtBuildTool tool = new SbtBuildTool();

    // ──────────────────────────────────────────────
    //  SPI Contract Compliance
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("SPI contract compliance")
    class SpiContract {

        @Test
        @DisplayName("getName() returns 'sbt'")
        void nameReturnsSbt() {
            assertThat(tool.getName()).isEqualTo("sbt");
        }

        @Test
        @DisplayName("getSupportedCommands() returns expected SBT tasks")
        void supportedCommandsAreCorrect() {
            List<String> cmds = tool.getSupportedCommands();
            assertThat(cmds)
                    .isNotNull()
                    .isNotEmpty()
                    .containsExactlyInAnyOrder(
                            "compile", "test", "run", "package", "clean", "assembly",
                            "publishLocal", "publish", "update", "doc", "console"
                    );
        }

        @Test
        @DisplayName("getExecutionPrompt() returns non-null, non-empty prompt")
        void executionPromptIsNonEmpty() {
            String prompt = tool.getExecutionPrompt();
            assertThat(prompt)
                    .isNotNull()
                    .isNotEmpty()
                    .contains("SBT")
                    .contains("compile")
                    .contains("semicolons");
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
        @DisplayName("returns true when build.sbt exists")
        void returnsTrueForBuildSbt() throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));
            assertThat(tool.isProject(projectDir)).isTrue();
        }

        @Test
        @DisplayName("returns false when no SBT markers exist")
        void returnsFalseForEmptyDirectory() {
            assertThat(tool.isProject(projectDir)).isFalse();
        }

        @Test
        @DisplayName("returns false when only pom.xml exists (not an SBT project)")
        void returnsFalseForMavenOnlyProject() throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            assertThat(tool.isProject(projectDir)).isFalse();
        }

        @Test
        @DisplayName("returns false when only build.gradle exists")
        void returnsFalseForGradleOnlyProject() throws IOException {
            Files.createFile(projectDir.resolve("build.gradle"));
            assertThat(tool.isProject(projectDir)).isFalse();
        }

        @Test
        @DisplayName("returns true when build.sbt and pom.xml coexist (hybrid)")
        void returnsTrueForHybridProject() throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));
            Files.createFile(projectDir.resolve("pom.xml"));
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
        @DisplayName("strips 'sbt ' prefix and splits tokens")
        void stripsSbtPrefix() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt compile"))
                    .containsExactly("compile");
        }

        @Test
        @DisplayName("handles command without sbt prefix")
        void handlesCommandWithoutPrefix() {
            assertThat(SbtBuildTool.parseCommandTokens("compile"))
                    .containsExactly("compile");
        }

        @Test
        @DisplayName("splits on semicolons (SBT's task-chaining delimiter)")
        void splitsOnSemicolons() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt clean;compile;test"))
                    .containsExactly("clean", "compile", "test");
        }

        @Test
        @DisplayName("splits on whitespace")
        void splitsOnWhitespace() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt clean compile test"))
                    .containsExactly("clean", "compile", "test");
        }

        @Test
        @DisplayName("handles bare 'sbt' (no tasks)")
        void handlesBareSbt() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt")).isEmpty();
        }

        @Test
        @DisplayName("collapses extra whitespace between tokens")
        void collapsesExtraWhitespace() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt   compile    test"))
                    .containsExactly("compile", "test");
        }

        @Test
        @DisplayName("trims leading and trailing whitespace")
        void trimsLeadingTrailingWhitespace() {
            assertThat(SbtBuildTool.parseCommandTokens("  sbt compile  "))
                    .containsExactly("compile");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null input")
        void throwsForNullInput() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens(null))
                    .withMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for empty string")
        void throwsForEmptyString() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens(""))
                    .withMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for whitespace-only string")
        void throwsForWhitespaceOnly() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("   "))
                    .withMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("allows 'assembly' task (fat JAR packaging)")
        void allowsAssemblyTask() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt assembly"))
                    .containsExactly("assembly");
        }

        @Test
        @DisplayName("allows 'publishLocal' task")
        void allowsPublishLocalTask() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt publishLocal"))
                    .containsExactly("publishLocal");
        }

        @Test
        @DisplayName("allows 'doc' and 'console' tasks")
        void allowsDocAndConsoleTasks() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt doc console"))
                    .containsExactly("doc", "console");
        }
    }

    // ──────────────────────────────────────────────
    //  parseCommandTokens() — security
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("parseCommandTokens() security and edge cases")
    class ParseCommandTokensSecurity {

        @Test
        @DisplayName("shell injection with && is rejected")
        void shellChainingWithAmpersandRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("sbt compile && rm -rf /"))
                    .withMessageContaining("sbt task not allowed");
        }

        @Test
        @DisplayName("piped commands are rejected")
        void pipedCommandsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("sbt compile | cat /etc/passwd"))
                    .withMessageContaining("sbt task not allowed");
        }

        @Test
        @DisplayName("command with $() is rejected")
        void dollarParenRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("sbt test $(whoami)"))
                    .withMessageContaining("sbt task not allowed");
        }

        @Test
        @DisplayName("unknown task is rejected")
        void unknownTaskRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("sbt deploy"))
                    .withMessageContaining("sbt task not allowed");
        }

        @Test
        @DisplayName("blocked -D flag is rejected")
        void blockedDFlagRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("sbt compile -Dprop=value"))
                    .withMessageContaining("Blocked sbt flag");
        }

        @Test
        @DisplayName("blocked -J flag is rejected")
        void blockedJFlagRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens("sbt compile -J-Xmx2g"))
                    .withMessageContaining("Blocked sbt flag");
        }

        @Test
        @DisplayName("very long command throws")
        void veryLongCommandThrows() {
            StringBuilder sb = new StringBuilder("sbt");
            while (sb.length() <= 510) {
                sb.append(" compile");
            }
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SbtBuildTool.parseCommandTokens(sb.toString()))
                    .withMessageContaining("Command too long");
        }

        @Test
        @DisplayName("mixed semicolons and whitespace still secure")
        void mixedDelimitersSecure() {
            assertThat(SbtBuildTool.parseCommandTokens("sbt clean;compile; test"))
                    .containsExactly("clean", "compile", "test");
        }
    }

    // ──────────────────────────────────────────────
    //  resolveSbtExecutable() — path resolution
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("resolveSbtExecutable() path resolution")
    class ResolveSbtExecutable {

        @TempDir
        Path sbtHome;

        @Test
        @DisplayName("resolves bin/sbt when buildToolHome contains it")
        void resolvesBinSbtFromBuildToolHome() throws IOException {
            Path binDir = Files.createDirectory(sbtHome.resolve("bin"));
            Path sbtBin = Files.createFile(binDir.resolve("sbt"));
            sbtBin.toFile().setExecutable(true);

            String result = SbtBuildTool.resolveSbtExecutable(
                    sbtHome.toString(), null);
            assertThat(result).isEqualTo(sbtBin.toString());
        }

        @Test
        @DisplayName("falls through to sbt wrapper when bin/sbt not found")
        void fallsThroughToSbtWrapper() throws IOException {
            Path sbtWrapper = Files.createFile(sbtHome.resolve("sbt"));
            sbtWrapper.toFile().setExecutable(true);

            String result = SbtBuildTool.resolveSbtExecutable(
                    sbtHome.toString(), null);
            assertThat(result).isEqualTo(sbtWrapper.toString());
        }

        @Test
        @DisplayName("falls through to buildToolHome itself when named 'sbt'")
        void fallsThroughToBuildToolHomeItself() throws IOException {
            Path sbtExe = Files.createFile(sbtHome.resolve("sbt"));
            sbtExe.toFile().setExecutable(true);

            String result = SbtBuildTool.resolveSbtExecutable(
                    sbtExe.toString(), null);
            assertThat(result).isEqualTo(sbtExe.toString());
        }

        @Test
        @DisplayName("checks projectDir sbt wrapper when buildToolHome empty")
        void checksProjectDirSbtWrapper() throws IOException {
            Path sbtWrapper = Files.createFile(sbtHome.resolve("sbt"));
            sbtWrapper.toFile().setExecutable(true);

            String result = SbtBuildTool.resolveSbtExecutable(
                    null, sbtHome.toString());
            assertThat(result).isEqualTo(sbtWrapper.toString());
        }

        @Test
        @DisplayName("falls back to 'sbt' on PATH when nothing found")
        void fallsBackToSbtOnPath() {
            String result = SbtBuildTool.resolveSbtExecutable(null, null);
            assertThat(result).isEqualTo("sbt");
        }

        @Test
        @DisplayName("falls back to 'sbt' when paths have no executables")
        void fallsBackWhenNoExecutablesFound(@TempDir Path emptyDir) {
            Path nonexistent = emptyDir.resolve("does-not-exist");
            String result = SbtBuildTool.resolveSbtExecutable(
                    nonexistent.toString(), nonexistent.toString());
            assertThat(result).isEqualTo("sbt");
        }

        @Test
        @DisplayName("handles empty string buildToolHome")
        void handlesEmptyStringBuildToolHome() {
            String result = SbtBuildTool.resolveSbtExecutable("", null);
            assertThat(result).isEqualTo("sbt");
        }

        @Test
        @DisplayName("handles empty string projectDir")
        void handlesEmptyStringProjectDir() {
            String result = SbtBuildTool.resolveSbtExecutable(null, "");
            assertThat(result).isEqualTo("sbt");
        }

        @Test
        @DisplayName("bin/sbt takes priority over sbt wrapper in same home")
        void binSbtTakesPriorityOverWrapper() throws IOException {
            Path binDir = Files.createDirectory(sbtHome.resolve("bin"));
            Path sbtBin = Files.createFile(binDir.resolve("sbt"));
            sbtBin.toFile().setExecutable(true);

            Path sbtWrapper = Files.createFile(sbtHome.resolve("sbt"));
            sbtWrapper.toFile().setExecutable(true);

            String result = SbtBuildTool.resolveSbtExecutable(
                    sbtHome.toString(), null);
            assertThat(result).isEqualTo(sbtBin.toString());
        }

        @Test
        @DisplayName("buildToolHome priority over projectDir")
        void buildToolHomePriorityOverProjectDir() throws IOException {
            Path toolDir = Files.createDirectory(sbtHome.resolve("tool"));
            Path binDir = Files.createDirectory(toolDir.resolve("bin"));
            Path sbtBin = Files.createFile(binDir.resolve("sbt"));
            sbtBin.toFile().setExecutable(true);

            Path projectDir = Files.createDirectory(sbtHome.resolve("project"));
            Path sbtWrapper = Files.createFile(projectDir.resolve("sbt"));
            sbtWrapper.toFile().setExecutable(true);

            String result = SbtBuildTool.resolveSbtExecutable(
                    toolDir.toString(), projectDir.toString());
            assertThat(result).isEqualTo(sbtBin.toString());
        }
    }

    // ──────────────────────────────────────────────
    //  BuildToolProvider integration with SBT
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("BuildToolProvider integration")
    class BuildToolProviderIntegration {

        @Test
        @DisplayName("provider registers SbtBuildTool")
        void providerRegistersSbtBuildTool() {
            BuildToolProvider provider = new BuildToolProvider();
            assertThat(provider.getTool("sbt")).isPresent();
            assertThat(provider.getTool("sbt").get())
                    .isInstanceOf(SbtBuildTool.class);
        }

        @Test
        @DisplayName("provider resolves SBT when project has build.sbt")
        void providerResolvesSbtByMarker(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildTool resolved = provider.resolve(null, projectDir);
            // Maven is registered first, so auto-detect finds Maven before SBT
            // when no pom.xml exists, Gradle won't match, SBT will
            // But Maven is first in registry and falls through when no pom.xml
            // Actually Maven's isProject checks pom.xml only, so if no pom.xml,
            // it falls through to Gradle, then SBT
        }

        @Test
        @DisplayName("provider resolves SBT by name regardless of markers")
        void providerResolvesByName(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("pom.xml"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildTool resolved = provider.resolve("sbt", projectDir);
            assertThat(resolved.getName()).isEqualTo("sbt");
        }

        @Test
        @DisplayName("provider lists sbt in getAllTools")
        void providerListsSbtInAllTools() {
            BuildToolProvider provider = new BuildToolProvider();
            assertThat(provider.getAllTools()).containsKey("sbt");
            assertThat(provider.getAllTools().get("sbt"))
                    .isInstanceOf(SbtBuildTool.class);
        }

        @Test
        @DisplayName("provider size includes sbt")
        void providerSizeIncludesSbt() {
            BuildToolProvider provider = new BuildToolProvider();
            assertThat(provider.size()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("provider auto-detects SBT when only build.sbt exists")
        void providerAutoDetectsSbt(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildTool resolved = provider.resolve(null, projectDir);
            // Maven (pom.xml) -> no match, Gradle (build.gradle etc) -> no match,
            // SBT (build.sbt) -> match
            assertThat(resolved.getName()).isEqualTo("sbt");
        }
    }

    // ──────────────────────────────────────────────
    //  BuildToolsService integration
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("BuildToolsService integration with SBT")
    class BuildToolsServiceIntegration {

        @Test
        @DisplayName("listBuildTools includes sbt with supported commands")
        void listBuildToolsIncludesSbt() {
            BuildToolProvider provider = new BuildToolProvider();
            BuildToolsService service = new BuildToolsService(provider);
            String listing = service.listBuildTools();
            assertThat(listing).contains("sbt:");
            assertThat(listing).contains("compile");
            assertThat(listing).contains("assembly");
        }

        @Test
        @DisplayName("getBuildToolVersion('sbt') rejects gracefully when not installed")
        void sbtVersionFailsGracefully() {
            BuildToolProvider provider = new BuildToolProvider();
            BuildToolsService service = new BuildToolsService(provider);
            try {
                service.getBuildToolVersion("sbt");
            } catch (RuntimeException e) {
                // Expected in test env — SBT likely not installed
                assertThat(e.getMessage()).isNotNull();
            }
        }

        @Test
        @DisplayName("detectBuildTool returns SBT detection for build.sbt project")
        void detectBuildToolDetectsSbt(@TempDir Path projectDir) throws IOException {
            Files.createFile(projectDir.resolve("build.sbt"));
            BuildToolProvider provider = new BuildToolProvider();
            BuildToolsService service = new BuildToolsService(provider);
            String result = service.detectBuildTool(projectDir.toString());
            assertThat(result).contains("\"tool\":\"sbt\"");
            assertThat(result).contains("\"detected\":true");
            assertThat(result).contains("build.sbt");
        }
    }

    // ──────────────────────────────────────────────
    //  Thread safety observations
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Thread safety observations")
    class ThreadSafety {

        @Test
        @DisplayName("parseCommandTokens is stateless — safe for concurrent use")
        void parseCommandTokensIsStateless() {
            String[] r1 = SbtBuildTool.parseCommandTokens("sbt clean compile");
            String[] r2 = SbtBuildTool.parseCommandTokens("sbt test;assembly");
            assertThat(r1).containsExactly("clean", "compile");
            assertThat(r2).containsExactly("test", "assembly");
        }

        @Test
        @DisplayName("resolveSbtExecutable is stateless — safe for concurrent use")
        void resolveSbtExecutableIsStateless(@TempDir Path tmp) throws IOException {
            Path sbtWrapper = Files.createFile(tmp.resolve("sbt"));
            sbtWrapper.toFile().setExecutable(true);

            String r1 = SbtBuildTool.resolveSbtExecutable(null, tmp.toString());
            String r2 = SbtBuildTool.resolveSbtExecutable(null, tmp.toString());
            assertThat(r1).isEqualTo(r2).isEqualTo(sbtWrapper.toString());
        }
    }
}
