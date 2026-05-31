package com.pragmatik.buildtools;

import org.apache.maven.shared.invoker.InvocationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MavenInvoker unit tests")
class MavenInvokerTest {

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
            assertThat(MavenInvoker.getCommands("mvn clean compile test"))
                    .containsExactly("clean", "compile", "test");
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
            assertThat(MavenInvoker.getCommands("mvn   clean    compile"))
                    .containsExactly("clean", "compile");
        }

        @Test
        @DisplayName("trims leading and trailing whitespace")
        void handlesLeadingTrailingWhitespace() {
            assertThat(MavenInvoker.getCommands("  mvn clean  "))
                    .containsExactly("clean");
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

    @Nested
    @DisplayName("invocationResultedInError()")
    class InvocationResultedInError {

        @Test
        @DisplayName("returns false when exit code is 0")
        void returnsFalseForExitCodeZero() {
            InvocationResult result = mock(InvocationResult.class);
            when(result.getExitCode()).thenReturn(0);
            assertThat(MavenInvoker.invocationResultedInError(result)).isFalse();
        }

        @Test
        @DisplayName("returns true when exit code is 1")
        void returnsTrueForExitCodeOne() {
            InvocationResult result = mock(InvocationResult.class);
            when(result.getExitCode()).thenReturn(1);
            assertThat(MavenInvoker.invocationResultedInError(result)).isTrue();
        }

        @Test
        @DisplayName("returns true when exit code is negative")
        void returnsTrueForNegativeExitCode() {
            InvocationResult result = mock(InvocationResult.class);
            when(result.getExitCode()).thenReturn(-1);
            assertThat(MavenInvoker.invocationResultedInError(result)).isTrue();
        }

        @Test
        @DisplayName("returns true for any non-zero exit code")
        void returnsTrueForAnyNonZeroExitCode() {
            for (int code : new int[]{2, 42, 255, Integer.MAX_VALUE}) {
                InvocationResult result = mock(InvocationResult.class);
                when(result.getExitCode()).thenReturn(code);
                assertThat(MavenInvoker.invocationResultedInError(result))
                        .as("exit code " + code + " should indicate error")
                        .isTrue();
            }
        }
    }
}
