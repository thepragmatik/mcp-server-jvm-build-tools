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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared helper for running an external {@link Process} synchronously without
 * the classic stdout/stderr pipe-deadlock.
 * <p>
 * <b>Root cause it fixes:</b> a process writes to both stdout and stderr through
 * fixed-size OS pipe buffers (typically ~64&nbsp;KB). If a caller drains stdout to
 * EOF <em>before</em> reading stderr (or vice-versa), the child process can block
 * forever once the un-drained pipe fills, because the un-drained pipe never makes
 * room for further writes — and stdout never reaches EOF because the process is
 * stuck. The fix is to drain stdout and stderr <em>concurrently</em> on dedicated
 * reader threads, mirroring the pattern used by
 * {@link AsyncBuildService#readProcessOutput}.
 * <p>
 * <b>Execution timeout:</b> {@link #run} replaces an unbounded
 * {@code process.waitFor()} with a bounded {@link Process#waitFor(long, TimeUnit)}.
 * On timeout the process tree is forcibly destroyed and an
 * {@link ExecutionTimeoutException} is thrown. The timeout defaults to
 * {@value #DEFAULT_TIMEOUT_SECONDS} seconds and is configurable through the
 * {@value #TIMEOUT_PROPERTY} system property, with a safe fallback to the default
 * on missing, blank, malformed, or non-positive values.
 */
public final class SyncProcessRunner {

    /** System property controlling the synchronous execution timeout (in seconds). */
    public static final String TIMEOUT_PROPERTY = "buildtools.exec.timeout-seconds";

    /** Default execution timeout in seconds when the property is unset or invalid. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 600L;

    /** Smallest accepted timeout; values below this fall back to the default. */
    static final long MIN_TIMEOUT_SECONDS = 1L;

    /** How long to wait for reader threads to finish draining after completion. */
    private static final long READER_JOIN_MILLIS = 5_000L;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private SyncProcessRunner() {
        // Static utility holder.
    }

    /**
     * Result of a synchronous process execution.
     *
     * @param exitCode the process exit code
     * @param stdout   the retained beginning and end of standard output
     * @param stderr   the retained beginning and end of standard error
     * @param stdoutTruncated whether standard output exceeded the capture limit
     * @param stderrTruncated whether standard error exceeded the capture limit
     */
    public record Result(
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            java.util.List<String> diagnostics,
            boolean diagnosticsTruncated) {
        public String withDiagnostics(String output) {
            return prependDiagnostics(output, true);
        }

        public String withMissingDiagnostics(String output) {
            return prependDiagnostics(output, false);
        }

        private String prependDiagnostics(String output, boolean includeRetained) {
            StringBuilder result = new StringBuilder();
            // Execution must keep every candidate at the front: its later
            // private-envelope cap can clip an occurrence in the edge snapshot.
            for (String line : diagnostics) {
                if (includeRetained || !output.contains(line)) {
                    result.append(line).append('\n');
                }
            }
            return result.append(output).toString();
        }
    }

    /**
     * Thrown when a synchronous process exceeds the configured execution timeout.
     * The offending process tree is terminated before this exception is raised.
     */
    public static final class ExecutionTimeoutException extends RuntimeException {
        public ExecutionTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Resolves the configured execution timeout in seconds.
     * <p>
     * Reads the {@value #TIMEOUT_PROPERTY} system property and falls back to
     * {@link #DEFAULT_TIMEOUT_SECONDS} when it is unset, blank, non-numeric, or
     * not strictly positive.
     *
     * @return the effective timeout in seconds (always &ge; {@link #MIN_TIMEOUT_SECONDS})
     */
    public static long resolveTimeoutSeconds() {
        String raw = System.getProperty(TIMEOUT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < MIN_TIMEOUT_SECONDS) {
                return DEFAULT_TIMEOUT_SECONDS;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /**
     * Runs the given process to completion using the configured timeout, draining
     * stdout and stderr concurrently.
     *
     * @param process the already-started process
     * @param label   a short label used to name the reader threads (e.g. "gradle")
     * @return the exit code and drained output streams
     * @throws IOException                if a reader thread fails irrecoverably
     * @throws InterruptedException       if the calling thread is interrupted while waiting
     * @throws ExecutionTimeoutException  if the process exceeds the configured timeout
     */
    public static Result run(Process process, String label) throws IOException, InterruptedException {
        return run(process, label, resolveTimeoutSeconds(), TimeUnit.SECONDS, false);
    }

    /** Capture bounded complete failure lines in addition to the output edges. */
    public static Result runWithDiagnostics(Process process, String label) throws IOException, InterruptedException {
        if (!"gradle".equals(label) && !"sbt".equals(label)) {
            throw new IllegalArgumentException("Unsupported diagnostic stream");
        }
        return run(process, label, resolveTimeoutSeconds(), TimeUnit.SECONDS, true);
    }

    /**
     * Runs the given process to completion using an explicit timeout, draining
     * stdout and stderr concurrently on dedicated reader threads.
     *
     * @param process the already-started process
     * @param label   a short label used to name the reader threads
     * @param timeout the maximum time to wait for completion
     * @param unit    the unit of {@code timeout}
     * @return the exit code and drained output streams
     * @throws IOException                if a reader thread fails irrecoverably
     * @throws InterruptedException       if the calling thread is interrupted while waiting
     * @throws ExecutionTimeoutException  if the process exceeds the supplied timeout
     */
    public static Result run(Process process, String label, long timeout, TimeUnit unit)
            throws IOException, InterruptedException {
        return run(process, label, timeout, unit, false);
    }

    private static Result run(Process process, String label, long timeout, TimeUnit unit, boolean collectDiagnostics)
            throws IOException, InterruptedException {
        BoundedProcessOutput out = new BoundedProcessOutput();
        BoundedProcessOutput err = new BoundedProcessOutput();
        BuildDiagnosticOutput outDiagnostics = collectDiagnostics ? new BuildDiagnosticOutput(out, label) : null;
        BuildDiagnosticOutput errDiagnostics = collectDiagnostics ? new BuildDiagnosticOutput(err, label) : null;

        Thread outThread =
                drain(process.getInputStream(), collectDiagnostics ? outDiagnostics : out, "sync-stdout-" + label);
        Thread errThread =
                drain(process.getErrorStream(), collectDiagnostics ? errDiagnostics : err, "sync-stderr-" + label);

        boolean finished;
        try {
            finished = process.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            terminateTree(process);
            joinQuietly(outThread);
            joinQuietly(errThread);
            throw e;
        }

        if (!finished) {
            terminateTree(process);
            joinQuietly(outThread);
            joinQuietly(errThread);
            throw new ExecutionTimeoutException("Process '" + label + "' timed out after " + timeout + " "
                    + unit.toString().toLowerCase()
                    + " and was forcibly terminated. Increase the timeout via the '"
                    + TIMEOUT_PROPERTY
                    + "' system property if the build legitimately " + "needs longer.");
        }

        // Process exited; let the readers finish draining whatever remains buffered.
        try {
            outThread.join(READER_JOIN_MILLIS);
            errThread.join(READER_JOIN_MILLIS);
        } catch (InterruptedException e) {
            terminateTree(process);
            throw e;
        }
        if (outThread.isAlive() || errThread.isAlive()) {
            terminateTree(process);
            throw new IOException("Process output readers did not finish after process exit");
        }

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        boolean diagnosticsTruncated = false;
        if (collectDiagnostics) {
            // The reader closes the source, not the sink. Only a successful join
            // proves that the final partial line is complete.
            errDiagnostics.finishAtEof();
            outDiagnostics.finishAtEof();
            candidates.addAll(errDiagnostics.diagnostics());
            candidates.addAll(outDiagnostics.diagnostics());
            diagnosticsTruncated = errDiagnostics.diagnosticsTruncated() || outDiagnostics.diagnosticsTruncated();
            if (candidates.size() > 13) {
                diagnosticsTruncated = true;
            }
        }
        return new Result(
                process.exitValue(),
                out.snapshot(),
                err.snapshot(),
                out.truncated(),
                err.truncated(),
                candidates.stream().limit(13).toList(),
                diagnosticsTruncated);
    }

    /** Drain raw bytes so a single unterminated line cannot allocate without bound. */
    public static Thread drain(InputStream stream, OutputStream sink, String threadName) {
        Thread thread = new Thread(
                () -> {
                    try (InputStream source = stream) {
                        byte[] chunk = new byte[8192];
                        int count;
                        while ((count = source.read(chunk)) != -1) {
                            sink.write(chunk, 0, count);
                        }
                    } catch (IOException ignored) {
                        // A terminated process closes its pipes; never log raw stream data.
                    }
                },
                threadName + "-" + THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Destroy descendants first so inherited pipe handles cannot outlive the build. */
    public static void terminateTree(Process process) {
        ProcessHandle[] descendants = process.toHandle().descendants().toArray(ProcessHandle[]::new);
        for (int i = descendants.length - 1; i >= 0; i--) {
            descendants[i].destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(READER_JOIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Reader threads also close their stream when termination completes.
        }
    }

    /**
     * Starts a dedicated reader thread that drains the given stream line-by-line
     * into the supplied buffer, appending {@link System#lineSeparator()} after each
     * line. The thread terminates on EOF or when the underlying stream is closed
     * (for example, after the process is destroyed).
     *
     * @param stream     the stream to drain (stdout or stderr of a process)
     * @param sink       the buffer to append decoded lines to (synchronized on itself)
     * @param threadName the reader thread name
     * @return the already-started reader thread
     */
    public static Thread drain(InputStream stream, StringBuilder sink, String threadName) {
        Thread thread = new Thread(
                () -> {
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            synchronized (sink) {
                                sink.append(line).append(System.lineSeparator());
                            }
                        }
                    } catch (IOException ignored) {
                        // Stream closed (e.g. process destroyed) — nothing more to drain.
                    }
                },
                threadName + "-" + THREAD_SEQ.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(READER_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
