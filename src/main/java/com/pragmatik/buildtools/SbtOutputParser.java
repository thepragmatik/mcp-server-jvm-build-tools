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

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SBT CLI output into structured JSON.
 * <p>
 * Handles the standard SBT console output format (with {@code --no-colors}):
 * <ul>
 *   <li>{@code [success] Total time: N s, completed ...} — overall result</li>
 *   <li>{@code [info] Passed: Total N, Failed N, Errors N, Passed N} — ScalaTest summary</li>
 *   <li>{@code [info] Test run finished: N failed, ...} — JUnit via sbt</li>
 *   <li>{@code [error] File.scala:42: message} — errors with file:line</li>
 *   <li>{@code [warn] ...} — warnings and deprecation notices</li>
 *   <li>Stack traces with {@code at package.Class.method(File.scala:NN)}</li>
 * </ul>
 * <p>
 * <b>First-mover:</b> No SBT output parser for MCP existed before this.
 */
public class SbtOutputParser implements BuildOutputParser {

    // "[success] Total time: 3 s, completed ..."
    private static final Pattern SUCCESS_PATTERN =
            Pattern.compile("\\[success]\\s+Total time:\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(s|ms|min|m)");

    // "[error] Total time: ..." on failure
    private static final Pattern FAILURE_PATTERN =
            Pattern.compile("\\[error]\\s+Total time:\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(s|ms|min|m)");

    // ScalaTest: "[info] Passed: Total N, Failed N, Errors N, Passed N"
    private static final Pattern SCALATEST_SUMMARY_PATTERN = Pattern.compile(
            "\\[info]\\s+Passed:.*?Total\\s+(\\d+),\\s*Failed\\s+(\\d+),\\s*Errors\\s+(\\d+),\\s*Passed\\s+(\\d+)");

    // JUnit via sbt: "[info] Test run finished: N failed, N ignored, N total, ..."
    private static final Pattern JUNIT_SUMMARY_PATTERN = Pattern.compile(
            "\\[info]\\s+Test run finished:\\s*(\\d+)\\s*failed,\\s*(\\d+)\\s*ignored,\\s*(\\d+)\\s*total");

    // specs2: "[info] Total for specification X: N examples, M failures, ..."
    private static final Pattern SPECS2_SUMMARY_PATTERN =
            Pattern.compile("\\[info]\\s+Total for specification.*?:?\\s*(\\d+)\\s*examples?.*?(\\d+)\\s*failures?");

    // File:line error: "[error] /path/File.scala:42: message"
    private static final Pattern ERROR_FILE_LINE_PATTERN =
            Pattern.compile("\\[error]\\s+(.+?\\.[a-zA-Z]+):(\\d+):\\s*(.*)");

    // Stack trace: "at package.Class.method(File.scala:42)"
    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile("at\\s+\\S+\\(([^:]+):(\\d+)\\)");

    // Generic [error] line (catch-all for errors without file:line)
    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile("\\[error]\\s+(.*)");

    // Generic [warn] line
    private static final Pattern WARNING_LINE_PATTERN = Pattern.compile("\\[warn]\\s+(.*)");

    // Duration fallback
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Total time:\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(s|ms|min|m)");

    @Override
    public String getToolName() {
        return "sbt";
    }

    @Override
    public Map<String, Object> parse(String rawOutput, int exitCode, String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", "sbt");
        result.put("command", command);

        if (rawOutput == null || rawOutput.isBlank()) {
            result.put("success", exitCode == 0);
            result.put("testSummary", emptyTestSummary());
            result.put("errors", Collections.emptyList());
            result.put("warnings", Collections.emptyList());
            result.put("duration", "0s");
            result.put("rawOutput", "");
            result.put("errorCount", 0);
            result.put("warningCount", 0);
            return result;
        }

        String[] lines = rawOutput.split("\\r?\\n");

        boolean success = exitCode == 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        String duration = null;

        int totalTests = 0, failedTests = 0, errorTests = 0;
        int passedTests = 0, skippedTests = 0;

        Map<String, Object> lastFileLineError = null;

        for (String line : lines) {

            // Check for [success] marker
            Matcher successMatcher = SUCCESS_PATTERN.matcher(line);
            if (successMatcher.find()) {
                if (duration == null) duration = successMatcher.group(1) + successMatcher.group(2);
                continue;
            }

            // Check for [error] Total time marker
            Matcher failureMatcher = FAILURE_PATTERN.matcher(line);
            if (failureMatcher.find()) {
                success = false;
                if (duration == null) duration = failureMatcher.group(1) + failureMatcher.group(2);
                continue;
            }

            // Duration fallback
            Matcher durMatcher = DURATION_PATTERN.matcher(line);
            if (durMatcher.find() && duration == null) {
                duration = durMatcher.group(1) + durMatcher.group(2);
            }

            // ScalaTest summary
            Matcher scalaTestMatcher = SCALATEST_SUMMARY_PATTERN.matcher(line);
            if (scalaTestMatcher.find()) {
                totalTests += Integer.parseInt(scalaTestMatcher.group(1));
                failedTests += Integer.parseInt(scalaTestMatcher.group(2));
                errorTests += Integer.parseInt(scalaTestMatcher.group(3));
                passedTests += Integer.parseInt(scalaTestMatcher.group(4));
                continue;
            }

            // JUnit summary
            Matcher junitMatcher = JUNIT_SUMMARY_PATTERN.matcher(line);
            if (junitMatcher.find()) {
                int f = Integer.parseInt(junitMatcher.group(1));
                int ig = Integer.parseInt(junitMatcher.group(2));
                int t = Integer.parseInt(junitMatcher.group(3));
                totalTests += t;
                failedTests += f;
                skippedTests += ig;
                passedTests += (t - f - ig);
                continue;
            }

            // specs2 summary
            Matcher specs2Matcher = SPECS2_SUMMARY_PATTERN.matcher(line);
            if (specs2Matcher.find()) {
                int t = Integer.parseInt(specs2Matcher.group(1));
                int f = Integer.parseInt(specs2Matcher.group(2));
                totalTests += t;
                failedTests += f;
                passedTests += (t - f);
                continue;
            }

            // File:line error
            Matcher errFileMatcher = ERROR_FILE_LINE_PATTERN.matcher(line);
            if (errFileMatcher.find()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("file", errFileMatcher.group(1));
                try {
                    err.put("line", Integer.parseInt(errFileMatcher.group(2)));
                } catch (NumberFormatException e) {
                    err.put("line", 0);
                }
                err.put("severity", "ERROR");
                String msg = errFileMatcher.group(3).trim();
                if (!msg.isEmpty()) err.put("message", msg);
                errors.add(err);
                lastFileLineError = err;
                continue;
            }

            // Stack trace following a file:line error
            if (lastFileLineError != null && line.contains("at ")) {
                Matcher stackMatcher = STACK_TRACE_PATTERN.matcher(line);
                if (stackMatcher.find() && !lastFileLineError.containsKey("stackFile")) {
                    lastFileLineError.put("stackFile", stackMatcher.group(1));
                    try {
                        lastFileLineError.put("stackLine", Integer.parseInt(stackMatcher.group(2)));
                    } catch (NumberFormatException e) {
                        lastFileLineError.put("stackLine", 0);
                    }
                    lastFileLineError.put("stackOrigin", line.trim());
                }
                continue;
            } else {
                lastFileLineError = null;
            }

            // Generic [error] catch-all (skip the "Total time" ones already handled)
            Matcher errLineMatcher = ERROR_LINE_PATTERN.matcher(line);
            if (errLineMatcher.find()) {
                String msg = errLineMatcher.group(1).trim();
                if (!msg.isEmpty() && !msg.startsWith("Total time")) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("severity", "ERROR");
                    err.put("message", msg);
                    errors.add(err);
                }
                continue;
            }

            // [warn] lines
            Matcher warnMatcher = WARNING_LINE_PATTERN.matcher(line);
            if (warnMatcher.find()) {
                String msg = warnMatcher.group(1).trim();
                if (!msg.isEmpty()) {
                    Map<String, Object> warn = new LinkedHashMap<>();
                    warn.put("severity", "WARNING");
                    warn.put("message", msg);
                    warnings.add(warn);
                }
            }
        }

        result.put("success", success);
        result.put("testSummary", buildTestSummary(totalTests, passedTests, failedTests, errorTests, skippedTests));
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("duration", duration != null ? duration : "unknown");
        result.put("rawOutput", rawOutput);
        result.put("errorCount", errors.size());
        result.put("warningCount", warnings.size());

        return result;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static Map<String, Object> emptyTestSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        summary.put("passed", 0);
        summary.put("failed", 0);
        summary.put("errors", 0);
        summary.put("skipped", 0);
        return summary;
    }

    private static Map<String, Object> buildTestSummary(int total, int passed, int failed, int error, int skipped) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("errors", error);
        summary.put("skipped", skipped);
        return summary;
    }
}
