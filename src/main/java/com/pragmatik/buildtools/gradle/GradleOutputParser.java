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
package com.pragmatik.buildtools.gradle;

import com.pragmatik.buildtools.build.BuildOutputParser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle CLI output into structured JSON.
 * <p>
 * Handles the standard Gradle console output format (with {@code --console=plain}):
 * <ul>
 *   <li>{@code BUILD SUCCESSFUL in Xs} / {@code BUILD FAILED in Xs} — overall result</li>
 *   <li>{@code N tests completed, M failed} — test summary</li>
 *   <li>{@code className > testMethod() FAILED} — individual test failures</li>
 *   <li>{@code > Task :X FAILED} — task-level failures</li>
 *   <li>{@code FAILURE: Build failed with an exception.} — failure reason</li>
 * </ul>
 */
public class GradleOutputParser implements BuildOutputParser {

    // Build result: "BUILD SUCCESSFUL in 5s" or "BUILD FAILED in 5s"
    private static final Pattern BUILD_RESULT_PATTERN =
            Pattern.compile("BUILD\\s+(SUCCESSFUL|FAILED)\\s+in\\s+([0-9]+(?:\\.[0-9]+)?)(ms|s|m|min)");

    // Test summary: "25 tests completed, 0 failed"
    private static final Pattern TEST_SUMMARY_PATTERN =
            Pattern.compile("(\\d+)\\s+test(?:s)?\\s+completed,\\s*(\\d+)\\s+failed");

    // Individual test failure: "com.example.ServiceTest > testMethod() FAILED"
    private static final Pattern TEST_FAILURE_PATTERN = Pattern.compile("^\\s*(\\S+)\\s+>\\s+(\\S+)\\(\\)\\s+FAILED");

    // Task failure: "> Task :compileJava FAILED"
    private static final Pattern TASK_FAILED_PATTERN = Pattern.compile(">\\s+Task\\s+(\\S+)\\s+FAILED");

    // What went wrong section
    private static final Pattern WHAT_WENT_WRONG_PATTERN = Pattern.compile("Execution failed for task\\s+'([^']+)'");

    // Warning pattern
    private static final Pattern WARNING_PATTERN = Pattern.compile("(?i)(?:warn(?:ing)?|deprecated)\\b[:\\s]*(.*)");

    // Stack trace file location
    private static final Pattern STACK_TRACE_FILE_PATTERN = Pattern.compile("at\\s+\\S+\\(([^:]+):(\\d+)\\)");

    @Override
    public String getToolName() {
        return "gradle";
    }

    @Override
    public Map<String, Object> parse(String rawOutput, int exitCode, String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", "gradle");
        result.put("command", command);

        if (rawOutput == null || rawOutput.isBlank()) {
            result.put("success", false);
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
        Map<String, Object> testSummary = null;
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        String duration = null;

        int totalTests = 0;
        int failedTests = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Parse BUILD SUCCESSFUL / BUILD FAILED
            Matcher buildMatcher = BUILD_RESULT_PATTERN.matcher(line);
            if (buildMatcher.find()) {
                success = "SUCCESSFUL".equals(buildMatcher.group(1));
                duration = buildMatcher.group(2) + buildMatcher.group(3);
            }

            // Parse test summary line
            Matcher testSumMatcher = TEST_SUMMARY_PATTERN.matcher(line);
            if (testSumMatcher.find()) {
                totalTests = Integer.parseInt(testSumMatcher.group(1));
                failedTests = Integer.parseInt(testSumMatcher.group(2));
            }

            // Parse individual test failures
            Matcher testFailMatcher = TEST_FAILURE_PATTERN.matcher(line);
            if (testFailMatcher.find()) {
                Map<String, Object> testErr = new LinkedHashMap<>();
                testErr.put("class", testFailMatcher.group(1));
                testErr.put("test", testFailMatcher.group(2));
                testErr.put("severity", "ERROR");
                testErr.put(
                        "message",
                        "Test " + testFailMatcher.group(2) + "() in " + testFailMatcher.group(1) + " FAILED");

                // Try to extract file:line from subsequent stack trace lines
                for (int j = i + 1; j < Math.min(i + 5, lines.length); j++) {
                    String nextLine = lines[j].trim();
                    Matcher stackMatcher = STACK_TRACE_FILE_PATTERN.matcher(nextLine);
                    if (stackMatcher.find()) {
                        testErr.put("file", stackMatcher.group(1));
                        try {
                            testErr.put("line", Integer.parseInt(stackMatcher.group(2)));
                        } catch (NumberFormatException e) {
                            testErr.put("line", 0);
                        }
                        break;
                    }
                    if (nextLine.isEmpty()) break;
                }

                errors.add(testErr);
                continue;
            }

            // Parse task failures
            Matcher taskFailMatcher = TASK_FAILED_PATTERN.matcher(line);
            if (taskFailMatcher.find()) {
                Map<String, Object> taskErr = new LinkedHashMap<>();
                taskErr.put("task", taskFailMatcher.group(1));
                taskErr.put("severity", "ERROR");
                taskErr.put("message", "Task " + taskFailMatcher.group(1) + " FAILED");
                errors.add(taskErr);
            }

            // Parse "FAILURE: Build failed" section and "What went wrong"
            if (line.contains("FAILURE: Build failed") || line.contains("* What went wrong:")) {
                // Collect detail lines after the header
                StringBuilder detail = new StringBuilder();
                int start = line.contains("* What went wrong:") ? i + 1 : i;
                for (int j = start; j < Math.min(start + 10, lines.length); j++) {
                    String nextLine = lines[j].trim();
                    if (nextLine.startsWith("* ") || nextLine.isEmpty()) {
                        if (nextLine.isEmpty() && detail.length() > 0) break;
                        continue;
                    }
                    if (!nextLine.isEmpty() && !nextLine.startsWith("> ")) {
                        if (detail.length() > 0) detail.append(" ");
                        detail.append(nextLine);
                    } else if (detail.length() > 0 && nextLine.isEmpty()) {
                        break;
                    }
                }
                // Don't add duplicate error messages
                boolean alreadyAdded = errors.stream()
                        .anyMatch(e -> e.get("message") != null
                                && e.get("message").toString().contains("Execution failed"));
                if (!alreadyAdded && detail.length() > 0) {
                    Map<String, Object> execErr = new LinkedHashMap<>();
                    execErr.put("severity", "ERROR");
                    execErr.put("message", detail.toString());
                    errors.add(execErr);
                }
            }

            // Parse warnings
            Matcher warnMatcher = WARNING_PATTERN.matcher(line);
            if (warnMatcher.find() && !line.contains("FAILURE:")) {
                String msg = warnMatcher.group(1) != null ? warnMatcher.group(1).trim() : line.trim();
                // Use the whole line if the captured group is empty
                if (msg.isEmpty()) msg = line.trim();
                Map<String, Object> warn = new LinkedHashMap<>();
                warn.put("severity", "WARNING");
                warn.put("message", msg);
                warnings.add(warn);
            }
        }

        // Build test summary
        if (totalTests > 0 || failedTests > 0) {
            testSummary = new LinkedHashMap<>();
            testSummary.put("total", totalTests);
            testSummary.put("failed", failedTests);
            testSummary.put("passed", totalTests - failedTests);
            testSummary.put("errors", 0);
            testSummary.put("skipped", 0);
        }

        result.put("success", success);
        result.put("testSummary", testSummary != null ? testSummary : emptyTestSummary());
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
}
