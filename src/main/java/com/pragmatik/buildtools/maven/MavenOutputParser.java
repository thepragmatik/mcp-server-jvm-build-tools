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

import com.pragmatik.buildtools.build.BuildOutputParser;
import com.pragmatik.buildtools.build.BuildResultLimits;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Maven CLI output into structured JSON.
 * <p>
 * Handles the standard Maven console output format:
 * <ul>
 *   <li>{@code BUILD SUCCESS} / {@code BUILD FAILURE} — overall result</li>
 *   <li>{@code Tests run: N, Failures: N, Errors: N, Skipped: N} — test summary</li>
 *   <li>{@code [ERROR]} lines — errors (including file:line locations)</li>
 *   <li>{@code [WARNING]} lines — warnings</li>
 *   <li>{@code Total time: X s} — build duration</li>
 * </ul>
 */
public class MavenOutputParser implements BuildOutputParser {

    // Test summary: "Tests run: 42, Failures: 1, Errors: 0, Skipped: 0"
    private static final Pattern TEST_SUMMARY_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
    private static final Pattern TEST_CLASS_SUFFIX_PATTERN = Pattern.compile("\\s-+\\s+in\\s+\\S+");
    private static final Pattern PLUGIN_BOUNDARY_PATTERN = Pattern.compile("^\\[INFO]\\s+---\\s+.+\\s+---\\s*$");

    // Error line with file:line: "[ERROR] /path/to/File.java:[45,12] message"
    private static final Pattern ERROR_FILE_LINE_PATTERN =
            Pattern.compile("\\[ERROR]\\s+(\\S+\\.java):?\\[?(\\d+)(?:,\\d+)?]?\\s*(.*)");

    // Build result: "BUILD SUCCESS" or "BUILD FAILURE"
    private static final Pattern BUILD_RESULT_PATTERN = Pattern.compile("BUILD\\s+(SUCCESS|FAILURE)");

    // Duration: "Total time:  12.345 s"
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Total time:\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(min|s|ms)");

    // Simple [WARNING] line
    private static final Pattern WARNING_SIMPLE_PATTERN = Pattern.compile("^\\[WARNING]\\s+(.*)");

    @Override
    public String getToolName() {
        return "maven";
    }

    @Override
    public Map<String, Object> parse(String rawOutput, int exitCode, String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", "maven");
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

        TestCounts completedTests = new TestCounts();
        TestCounts perClassFallback = new TestCounts();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // A plugin boundary closes an execution that emitted class results
            // but no final Results total (for example, interrupted output).
            if (PLUGIN_BOUNDARY_PATTERN.matcher(line).matches()) {
                completedTests.add(perClassFallback);
                perClassFallback.clear();
            }

            // Surefire/Failsafe print each class and then a final total. Use the
            // final total once; retain class counts only when it is absent.
            Matcher testMatcher = TEST_SUMMARY_PATTERN.matcher(line);
            if (testMatcher.find()) {
                if (TEST_CLASS_SUFFIX_PATTERN
                        .matcher(line.substring(testMatcher.end()))
                        .find()) {
                    perClassFallback.add(testMatcher);
                } else {
                    completedTests.add(testMatcher);
                    perClassFallback.clear();
                }
            }

            // Parse BUILD SUCCESS / BUILD FAILURE
            Matcher buildMatcher = BUILD_RESULT_PATTERN.matcher(line);
            if (buildMatcher.find()) {
                success = "SUCCESS".equals(buildMatcher.group(1));
            }

            // Parse duration
            Matcher durMatcher = DURATION_PATTERN.matcher(line);
            if (durMatcher.find()) {
                duration = durMatcher.group(1) + durMatcher.group(2);
            }

            // Parse [ERROR] lines with file:line info
            Matcher errFileMatcher = ERROR_FILE_LINE_PATTERN.matcher(line);
            if (errFileMatcher.find()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("file", errFileMatcher.group(1));
                err.put("line", Integer.parseInt(errFileMatcher.group(2)));
                err.put("severity", "ERROR");
                String msg = errFileMatcher.group(3).trim();
                if (!msg.isEmpty()) {
                    err.put("message", msg);
                }
                errors.add(err);
                continue;
            }

            // Parse simple [WARNING] lines
            Matcher warnSimpMatcher = WARNING_SIMPLE_PATTERN.matcher(line);
            if (warnSimpMatcher.find()) {
                String msg = warnSimpMatcher.group(1).trim();
                if (!msg.isEmpty()) {
                    Map<String, Object> warn = new LinkedHashMap<>();
                    warn.put("severity", "WARNING");
                    warn.put("message", msg);
                    warnings.add(warn);
                }
            }
        }

        completedTests.add(perClassFallback);

        result.put("success", success);
        result.put("testSummary", completedTests.toSummary());
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("duration", duration != null ? duration : "0s");
        result.put("rawOutput", rawOutput);
        result.put("errorCount", errors.size());
        result.put("warningCount", warnings.size());

        return result;
    }

    private Map<String, Object> emptyTestSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", 0);
        summary.put("passed", 0);
        summary.put("failed", 0);
        summary.put("errors", 0);
        summary.put("skipped", 0);
        return summary;
    }

    private static final class TestCounts {
        private int total;
        private int failures;
        private int errors;
        private int skipped;
        private boolean capped;

        void add(Matcher matcher) {
            total = cappedAdd(total, boundedCount(matcher.group(1)));
            failures = cappedAdd(failures, boundedCount(matcher.group(2)));
            errors = cappedAdd(errors, boundedCount(matcher.group(3)));
            skipped = cappedAdd(skipped, boundedCount(matcher.group(4)));
        }

        void add(TestCounts other) {
            total = cappedAdd(total, other.total);
            failures = cappedAdd(failures, other.failures);
            errors = cappedAdd(errors, other.errors);
            skipped = cappedAdd(skipped, other.skipped);
            capped |= other.capped;
        }

        void clear() {
            total = 0;
            failures = 0;
            errors = 0;
            skipped = 0;
            capped = false;
        }

        Map<String, Object> toSummary() {
            int boundedFailures = Math.min(failures, total);
            int boundedErrors = Math.min(errors, total - boundedFailures);
            int boundedSkipped = Math.min(skipped, total - boundedFailures - boundedErrors);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("passed", total - boundedFailures - boundedErrors - boundedSkipped);
            summary.put("failed", boundedFailures);
            summary.put("errors", boundedErrors);
            summary.put("skipped", boundedSkipped);
            if (capped || boundedFailures != failures || boundedErrors != errors || boundedSkipped != skipped) {
                summary.put("countsCapped", true);
            }
            return summary;
        }

        private int boundedCount(String digits) {
            int value = 0;
            for (int i = 0; i < digits.length(); i++) {
                int digit = digits.charAt(i) - '0';
                if (value > (BuildResultLimits.MAX_VISIBLE_COUNTER - digit) / 10) {
                    capped = true;
                    return BuildResultLimits.MAX_VISIBLE_COUNTER;
                }
                value = value * 10 + digit;
            }
            return value;
        }

        private int cappedAdd(int left, int right) {
            if (left > BuildResultLimits.MAX_VISIBLE_COUNTER - right) {
                capped = true;
                return BuildResultLimits.MAX_VISIBLE_COUNTER;
            }
            return left + right;
        }
    }
}
