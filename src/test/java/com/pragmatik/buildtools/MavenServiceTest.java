package com.pragmatik.buildtools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

@DisplayName("MavenService unit tests")
class MavenServiceTest {

    private final MavenService service = new MavenService();

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("executeCommand() validation")
    class ExecuteCommandValidation {

        @Test
        @DisplayName("throws when mavenHome is null")
        void throwsWhenMavenHomeIsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executeCommand(null, tempDir.toString(), "clean"))
                    .withMessageContaining("Maven home cannot be null");
        }

        @Test
        @DisplayName("throws when mavenHome does not exist")
        void throwsWhenMavenHomeDoesNotExist() {
            Path nonexistent = tempDir.resolve("nonexistent-maven");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executeCommand(nonexistent.toString(), tempDir.toString(), "clean"))
                    .withMessageContaining("Invalid maven home directory");
        }

        @Test
        @DisplayName("throws when mavenHome is a file not a directory")
        void throwsWhenMavenHomeIsFile(@TempDir Path anotherTemp) throws IOException {
            Path file = anotherTemp.resolve("maven.txt");
            Files.createFile(file);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executeCommand(file.toString(), anotherTemp.toString(), "clean"))
                    .withMessageContaining("Invalid maven home directory");
        }

        @Test
        @DisplayName("throws when projectDir is null")
        void throwsWhenProjectDirIsNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executeCommand(tempDir.toString(), null, "clean"))
                    .withMessageContaining("Maven project directory cannot be null");
        }

        @Test
        @DisplayName("throws when projectDir does not exist")
        void throwsWhenProjectDirDoesNotExist() {
            Path nonexistent = tempDir.resolve("nonexistent-project");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executeCommand(tempDir.toString(), nonexistent.toString(), "clean"))
                    .withMessageContaining("does not exist");
        }

        @Test
        @DisplayName("throws when projectDir is a file not a directory")
        void throwsWhenProjectDirIsFile(@TempDir Path anotherTemp) throws IOException {
            Path projectFile = anotherTemp.resolve("not-a-dir.txt");
            Files.createFile(projectFile);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executeCommand(anotherTemp.toString(), projectFile.toString(), "clean"))
                    .withMessageContaining("does not exist");
        }

        @Test
        @DisplayName("accepts valid inputs and delegates to invoker")
        void acceptsValidInputsAndDelegates() {
            try (MockedStatic<MavenInvoker> mocked = mockStatic(MavenInvoker.class)) {
                mocked.when(() -> MavenInvoker.getCommands(any()))
                        .thenReturn(new String[]{"clean"});
                mocked.when(() -> MavenInvoker.executeCommandUsingMavenInvoker(any(), any(), any()))
                        .thenReturn("BUILD SUCCESS");

                String result = service.executeCommand(
                        tempDir.toString(), tempDir.toString(), "clean");

                assertThat(result).isEqualTo("BUILD SUCCESS");
            }
        }

        @Test
        @DisplayName("sets maven.home system property from valid mavenHome")
        void setsMavenHomeSystemProperty() {
            try (MockedStatic<MavenInvoker> mocked = mockStatic(MavenInvoker.class)) {
                mocked.when(() -> MavenInvoker.getCommands(any()))
                        .thenReturn(new String[]{"clean"});
                mocked.when(() -> MavenInvoker.executeCommandUsingMavenInvoker(any(), any(), any()))
                        .thenReturn("BUILD SUCCESS");

                service.executeCommand(tempDir.toString(), tempDir.toString(), "clean");

                assertThat(System.getProperty("maven.home")).isEqualTo(tempDir.toString());
            }
        }

        @Test
        @DisplayName("sets multiModuleProjectDirectory from valid projectDir")
        void setsMultiModuleProjectDirSystemProperty() {
            try (MockedStatic<MavenInvoker> mocked = mockStatic(MavenInvoker.class)) {
                mocked.when(() -> MavenInvoker.getCommands(any()))
                        .thenReturn(new String[]{"compile"});
                mocked.when(() -> MavenInvoker.executeCommandUsingMavenInvoker(any(), any(), any()))
                        .thenReturn("BUILD SUCCESS");

                service.executeCommand(tempDir.toString(), tempDir.toString(), "compile");

                assertThat(System.getProperty("maven.multiModuleProjectDirectory"))
                        .isEqualTo(tempDir.toString());
            }
        }
    }

    @Nested
    @DisplayName("executeCommand() delegation")
    class ExecuteCommandDelegation {

        @Test
        @DisplayName("passes parsed commands to MavenInvoker")
        void passesParsedCommandsToInvoker() {
            try (MockedStatic<MavenInvoker> mocked = mockStatic(MavenInvoker.class)) {
                mocked.when(() -> MavenInvoker.getCommands("mvn clean install"))
                        .thenReturn(new String[]{"clean", "install"});
                mocked.when(() -> MavenInvoker.executeCommandUsingMavenInvoker(
                        eq(tempDir.toString()),
                        eq(new String[]{"clean", "install"}),
                        eq(tempDir.toString())))
                        .thenReturn("BUILD SUCCESS");

                String result = service.executeCommand(
                        tempDir.toString(), tempDir.toString(), "mvn clean install");

                assertThat(result).isEqualTo("BUILD SUCCESS");
                mocked.verify(() -> MavenInvoker.getCommands("mvn clean install"));
                mocked.verify(() -> MavenInvoker.executeCommandUsingMavenInvoker(
                        eq(tempDir.toString()),
                        eq(new String[]{"clean", "install"}),
                        eq(tempDir.toString())));
            }
        }

        @Test
        @DisplayName("propagates RuntimeException from invoker")
        void propagatesRuntimeExceptionFromInvoker() {
            try (MockedStatic<MavenInvoker> mocked = mockStatic(MavenInvoker.class)) {
                mocked.when(() -> MavenInvoker.getCommands(any()))
                        .thenReturn(new String[]{"test"});
                mocked.when(() -> MavenInvoker.executeCommandUsingMavenInvoker(any(), any(), any()))
                        .thenThrow(new RuntimeException("BUILD FAILURE"));

                try {
                    service.executeCommand(tempDir.toString(), tempDir.toString(), "test");
                    throw new AssertionError("Expected RuntimeException was not thrown");
                } catch (RuntimeException e) {
                    assertThat(e.getMessage()).contains("BUILD FAILURE");
                }
            }
        }
    }

    @Nested
    @DisplayName("version()")
    class Version {

        @AfterEach
        void cleanup() {
            System.clearProperty("maven.multiModuleProjectDirectory");
        }

        @Test
        @DisplayName("delegates to MavenInvoker.executeUsingMavenEmbedder with --version")
        void delegatesToMavenEmbedder() {
            try (MockedStatic<MavenInvoker> mocked = mockStatic(MavenInvoker.class)) {
                mocked.when(() -> MavenInvoker.executeUsingMavenEmbedder(
                        eq(new String[]{"--version"}), eq(".")))
                        .thenReturn("Apache Maven 3.9.9");

                String result = service.version();

                assertThat(result).isEqualTo("Apache Maven 3.9.9");
                mocked.verify(() -> MavenInvoker.executeUsingMavenEmbedder(
                        new String[]{"--version"}, "."));
            }
        }
    }
}
