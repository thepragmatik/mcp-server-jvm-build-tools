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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

@DisplayName("MavenInvoker unit tests")
class MavenInvokerTest {
    @Test
    @DisplayName("analysis retains a compiler error from the discarded middle of a large Maven log")
    void analysisRetainsMiddleCompilerError(@TempDir Path projectDir) throws Exception {
        Path mavenHome = Files.createDirectory(projectDir.resolve("maven-home"));
        Path bin = Files.createDirectory(mavenHome.resolve("bin"));
        Path fakeMaven = bin.resolve("mvn");
        Files.writeString(
                fakeMaven,
                "#!/bin/sh\n"
                        + "yes '[INFO] padding' | head -n 4096\n"
                        + "printf '[ERROR] /synthetic/Sample.java:[42,1] cannot find symbol\\n'\n"
                        + "yes '[INFO] tail padding' | head -n 1400000\n"
                        + "printf '[INFO] BUILD FAILURE\\n'\n"
                        + "exit 1\n");
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();

        MavenInvoker.AnalysisResult result =
                MavenInvoker.executeForAnalysis(mavenHome.toString(), new String[] {"compile"}, projectDir.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(new MavenOutputParser()
                        .parse(result.output(), result.exitCode(), "compile")
                        .get("errorCount"))
                .isEqualTo(1);
        assertThat(result.output().length()).isLessThan(160_000);
    }

    @Test
    @DisplayName("analysis does not duplicate a retained head error when preserving a middle error")
    void analysisAvoidsRetainedDiagnosticDuplicates(@TempDir Path projectDir) throws Exception {
        Path mavenHome = Files.createDirectory(projectDir.resolve("maven-home"));
        Path bin = Files.createDirectory(mavenHome.resolve("bin"));
        Path fakeMaven = bin.resolve("mvn");
        Files.writeString(
                fakeMaven,
                "#!/bin/sh\n"
                        + "printf '[ERROR] /synthetic/Head.java:[3,1] cannot find symbol\\n'\n"
                        + "yes '[INFO] padding' | head -n 4096\n"
                        + "printf '[ERROR] /synthetic/Middle.java:[42,1] cannot find symbol\\n'\n"
                        + "yes '[INFO] tail padding' | head -n 1400000\n"
                        + "printf '[INFO] BUILD FAILURE\\n'\n"
                        + "exit 1\n");
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();

        MavenInvoker.AnalysisResult result =
                MavenInvoker.executeForAnalysis(mavenHome.toString(), new String[] {"compile"}, projectDir.toString());
        var parsed = new MavenOutputParser().parse(result.output(), result.exitCode(), "compile");

        assertThat(parsed.get("errorCount")).isEqualTo(2);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.diagnosticsTruncated()).isFalse();
        assertThat(result.output()).contains("BUILD FAILURE");
    }

    @Test
    @DisplayName("analysis counts one compiler error repeated in discarded stdout and stderr only once")
    void analysisDeduplicatesDiscardedErrorsAcrossStreams(@TempDir Path projectDir) throws Exception {
        Path mavenHome = Files.createDirectory(projectDir.resolve("maven-home"));
        Path bin = Files.createDirectory(mavenHome.resolve("bin"));
        Path fakeMaven = bin.resolve("mvn");
        Files.writeString(
                fakeMaven,
                "#!/bin/sh\n"
                        + "yes '[INFO] stdout head' | head -n 4096\n"
                        + "yes '[INFO] stderr head' | head -n 4096 >&2\n"
                        + "printf '[ERROR] /synthetic/Repeated.java:[42,1] cannot find symbol\\n'\n"
                        + "printf '[ERROR] /synthetic/Repeated.java:[42,1] cannot find symbol\\n' >&2\n"
                        + "yes '[INFO] stdout tail' | head -n 10000\n"
                        + "yes '[INFO] stderr tail' | head -n 10000 >&2\n"
                        + "printf '[INFO] BUILD FAILURE\\n'\n"
                        + "exit 1\n");
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();

        MavenInvoker.AnalysisResult result =
                MavenInvoker.executeForAnalysis(mavenHome.toString(), new String[] {"compile"}, projectDir.toString());
        var parsed = new MavenOutputParser().parse(result.output(), result.exitCode(), "compile");

        assertThat(result.outputTruncated()).isTrue();
        assertThat(parsed.get("errorCount")).isEqualTo(1);
    }

    @Nested
    @DisplayName("getCommands()")
    class GetCommands {

        @Test
        @DisplayName("strips mvn prefix from command")
        void stripsMvnPrefix() {
            assertThat(MavenInvoker.getCommands("mvn clean")).containsExactly("clean");
        }

        @Test
        @DisplayName("handles command without mvn prefix")
        void handlesCommandWithoutMvnPrefix() {
            assertThat(MavenInvoker.getCommands("clean")).containsExactly("clean");
        }

        @Test
        @DisplayName("splits multi-word command into arguments")
        void splitsMultiWordCommand() {
            assertThat(MavenInvoker.getCommands("mvn clean compile test")).containsExactly("clean", "compile", "test");
        }

        @Test
        @DisplayName("splits command with Maven options")
        void splitsCommandWithFlags() {
            assertThat(MavenInvoker.getCommands("mvn clean -DskipTests -T4"))
                    .containsExactly("clean", "-DskipTests", "-T4");
        }

        @Test
        @DisplayName("handles command with only mvn prefix")
        void handlesOnlyMvnPrefix() {
            assertThat(MavenInvoker.getCommands("mvn ")).isEmpty();
        }

        @Test
        @DisplayName("handles bare mvn without arguments")
        void handlesBareMvn() {
            assertThat(MavenInvoker.getCommands("mvn")).isEmpty();
        }

        @Test
        @DisplayName("collapses extra whitespace between tokens via split regex")
        void handlesExtraWhitespace() {
            assertThat(MavenInvoker.getCommands("mvn   clean    compile")).containsExactly("clean", "compile");
        }

        @Test
        @DisplayName("trims leading and trailing whitespace")
        void handlesLeadingTrailingWhitespace() {
            assertThat(MavenInvoker.getCommands("  mvn clean  ")).containsExactly("clean");
        }

        @Test
        @DisplayName("preserves parameter values with equals signs")
        void preservesParamValues() {
            assertThat(MavenInvoker.getCommands("mvn clean -Dmessage=hello-world"))
                    .containsExactly("clean", "-Dmessage=hello-world");
        }

        @Test
        @DisplayName("handles very long command strings")
        void handlesVeryLongCommand() {
            StringBuilder sb = new StringBuilder("mvn clean");
            for (int i = 0; i < 100; i++) {
                sb.append(" -Dprop").append(i).append("=value").append(i);
            }
            String[] result = MavenInvoker.getCommands(sb.toString());
            assertThat(result).hasSizeGreaterThan(100);
            assertThat(result[0]).isEqualTo("clean");
        }
    }

    @Nested
    @DisplayName("getCommands() edge cases")
    class GetCommandsEdgeCases {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("handles null and empty command input")
        void handlesNullOrEmpty(String input) {
            if (input == null) {
                try {
                    MavenInvoker.getCommands(null);
                } catch (NullPointerException e) {
                    // expected behavior for null input
                }
            } else {
                String[] result = MavenInvoker.getCommands(input);
                assertThat(result).isNotNull();
                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("rejects unallowlisted bare tokens after flag arguments")
        void rejectsUnallowlistedTokensAfterFlags() {
            // "hello" is not in ALLOWED_COMMANDS — security hardening rejects it
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean -Dcmd=echo hello"))
                    .withMessageContaining("Command not allowed");
        }
    }

    @Test
    @DisplayName("active Maven execution bounds an unterminated multi-megabyte line")
    void activeMavenExecutionBoundsLongLine(@TempDir Path projectDir) throws Exception {
        Path mavenHome = Files.createDirectory(projectDir.resolve("maven-home"));
        Path bin = Files.createDirectory(mavenHome.resolve("bin"));
        Path fakeMaven = bin.resolve("mvn");
        Files.writeString(
                fakeMaven, "#!/bin/sh\nprintf 'BEGIN'; yes x | tr -d '\\n' | head -c 25165824; printf 'END'\n");
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();

        String output = MavenInvoker.executeCommand(mavenHome.toString(), new String[] {"test"}, projectDir.toString());

        assertThat(output).startsWith("BEGIN").endsWith("END");
        assertThat(output.length()).isLessThanOrEqualTo(128 * 1024);
    }

    @Test
    @DisplayName("Maven failure retains diagnostic text from both pipes")
    void activeMavenExecutionPreservesFailurePipes(@TempDir Path projectDir) throws Exception {
        Path mavenHome = Files.createDirectory(projectDir.resolve("maven-home"));
        Path bin = Files.createDirectory(mavenHome.resolve("bin"));
        Path fakeMaven = bin.resolve("mvn");
        Files.writeString(fakeMaven, "#!/bin/sh\nprintf 'compile failed'\nprintf 'error detail' >&2\nexit 7\n");
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();

        assertThatThrownBy(() ->
                        MavenInvoker.executeCommand(mavenHome.toString(), new String[] {"test"}, projectDir.toString()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Maven exited with code 7")
                .hasMessageContaining("error detail")
                .hasMessageContaining("compile failed");
    }
}
