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
package com.pragmatik.buildtools.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for {@link SyncProcessRunner}.
 * <p>
 * The central regression guards against the stdout/stderr pipe-buffer deadlock
 * (GitHub issue #76): a child process that writes more than the OS pipe buffer
 * (~64&nbsp;KB) to one stream while the caller drains the other to EOF first would
 * block forever. {@link SyncProcessRunner} drains both streams concurrently, so
 * the process completes. Each process-spawning test carries a JUnit
 * {@link Timeout} so a regression manifests as a fast test failure rather than a
 * hung build.
 */
@DisplayName("SyncProcessRunner unit tests")
class SyncProcessRunnerTest {

    /** Comfortably exceeds a typical 64&nbsp;KB OS pipe buffer (~200&nbsp;KB of text). */
    private static final int FLOOD_LINES = 4_000;

    private String savedTimeoutProperty;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void captureProperty() {
        savedTimeoutProperty = System.getProperty(SyncProcessRunner.TIMEOUT_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (savedTimeoutProperty == null) {
            System.clearProperty(SyncProcessRunner.TIMEOUT_PROPERTY);
        } else {
            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, savedTimeoutProperty);
        }
    }

    private static void assumeShellAvailable() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute(), "POSIX /bin/sh required for process-spawning tests");
    }

    private static Process spawn(String shellScript) throws Exception {
        return new ProcessBuilder("/bin/sh", "-c", shellScript).start();
    }

    // ──────────────────────────────────────────────
    //  Pipe-deadlock regression (issue #76)
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("concurrent stream draining (deadlock regression)")
    class ConcurrentDraining {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName(">64KB of stderr does not deadlock and is fully captured")
        void largeStderrDoesNotDeadlock() throws Exception {
            assumeShellAvailable();
            // Emit ~200KB to stderr ONLY. Reading stdout to EOF before stderr (the
            // old, broken behaviour) would block the child once the stderr pipe
            // fills, hanging forever. Concurrent draining must complete promptly.
            Process process =
                    spawn("yes 'STDERR_PADDING_0123456789ABCDEF0123456789ABCDEF' | head -n " + FLOOD_LINES + " 1>&2");

            SyncProcessRunner.Result result = SyncProcessRunner.run(process, "test-stderr");

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEmpty();
            assertThat(result.stderr()).contains("STDERR_PADDING");
            assertThat(result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(128 * 1024);
            assertThat(result.stderrTruncated()).isTrue();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName(">64KB of stdout does not deadlock and is fully captured")
        void largeStdoutDoesNotDeadlock() throws Exception {
            assumeShellAvailable();
            Process process = spawn("yes 'STDOUT_PADDING_0123456789ABCDEF0123456789ABCDEF' | head -n " + FLOOD_LINES);

            SyncProcessRunner.Result result = SyncProcessRunner.run(process, "test-stdout");

            assertThat(result.exitCode()).isZero();
            assertThat(result.stderr()).isEmpty();
            assertThat(result.stdout()).contains("STDOUT_PADDING");
            assertThat(result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(128 * 1024);
            assertThat(result.stdoutTruncated()).isTrue();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("large output on BOTH streams is captured without deadlock")
        void largeBothStreamsDoNotDeadlock() throws Exception {
            assumeShellAvailable();
            Process process = spawn("yes 'BOTH_PADDING_0123456789ABCDEF' | head -n " + FLOOD_LINES + "; "
                    + "yes 'BOTH_PADDING_0123456789ABCDEF' | head -n " + FLOOD_LINES
                    + " 1>&2");

            SyncProcessRunner.Result result = SyncProcessRunner.run(process, "test-both");

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("BOTH_PADDING");
            assertThat(result.stderr()).contains("BOTH_PADDING");
            assertThat(result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(128 * 1024);
            assertThat(result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(128 * 1024);
        }
    }

    // ──────────────────────────────────────────────
    //  Execution timeout
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("execution timeout")
    class ExecutionTimeout {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a long-running process is forcibly terminated on timeout")
        void timeoutForcesTermination() throws Exception {
            assumeShellAvailable();
            Process process = spawn("sleep 60");

            assertThatThrownBy(() -> SyncProcessRunner.run(process, "test-timeout", 1, TimeUnit.SECONDS))
                    .isInstanceOf(SyncProcessRunner.ExecutionTimeoutException.class)
                    .hasMessageContaining("timed out")
                    .hasMessageContaining(SyncProcessRunner.TIMEOUT_PROPERTY);

            assertThat(process.isAlive()).isFalse();
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a process that finishes within the timeout returns normally")
        void fastProcessWithinTimeoutSucceeds() throws Exception {
            assumeShellAvailable();
            Process process = spawn("echo done");

            SyncProcessRunner.Result result = SyncProcessRunner.run(process, "test-fast", 30, TimeUnit.SECONDS);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("done");
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void timeoutTerminatesDescendantProcess() throws Exception {
            assumeShellAvailable();
            Path childPidFile = temporaryDirectory.resolve("child.pid");
            Process process = spawn("sleep 60 & echo $! > '" + childPidFile + "'; wait");
            for (int attempt = 0; attempt < 100 && !Files.exists(childPidFile); attempt++) {
                Thread.sleep(10);
            }
            assertThat(Files.exists(childPidFile)).isTrue();
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());

            assertThatThrownBy(() -> SyncProcessRunner.run(process, "tree", 1, TimeUnit.SECONDS))
                    .isInstanceOf(SyncProcessRunner.ExecutionTimeoutException.class);

            for (int attempt = 0;
                    attempt < 100
                            && ProcessHandle.of(childPid)
                                    .map(ProcessHandle::isAlive)
                                    .orElse(false);
                    attempt++) {
                Thread.sleep(10);
            }
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                    .isFalse();
        }
    }

    // ──────────────────────────────────────────────
    //  Timeout property resolution
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("resolveTimeoutSeconds()")
    class ResolveTimeoutSeconds {

        @Test
        @DisplayName("returns the default when the property is unset")
        void defaultWhenUnset() {
            System.clearProperty(SyncProcessRunner.TIMEOUT_PROPERTY);
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(SyncProcessRunner.DEFAULT_TIMEOUT_SECONDS);
        }

        @Test
        @DisplayName("returns the configured value when valid")
        void usesValidConfiguredValue() {
            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, "120");
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(120L);
        }

        @Test
        @DisplayName("falls back to default on a blank value")
        void fallsBackOnBlank() {
            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, "   ");
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(SyncProcessRunner.DEFAULT_TIMEOUT_SECONDS);
        }

        @Test
        @DisplayName("falls back to default on a malformed value")
        void fallsBackOnMalformed() {
            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, "not-a-number");
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(SyncProcessRunner.DEFAULT_TIMEOUT_SECONDS);
        }

        @Test
        @DisplayName("falls back to default on a non-positive value")
        void fallsBackOnNonPositive() {
            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, "0");
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(SyncProcessRunner.DEFAULT_TIMEOUT_SECONDS);

            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, "-5");
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(SyncProcessRunner.DEFAULT_TIMEOUT_SECONDS);
        }

        @Test
        @DisplayName("trims surrounding whitespace before parsing")
        void trimsWhitespace() {
            System.setProperty(SyncProcessRunner.TIMEOUT_PROPERTY, "  45  ");
            assertThat(SyncProcessRunner.resolveTimeoutSeconds()).isEqualTo(45L);
        }
    }
}
