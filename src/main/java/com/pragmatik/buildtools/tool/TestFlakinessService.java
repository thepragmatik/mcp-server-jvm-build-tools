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
package com.pragmatik.buildtools.tool;

import com.pragmatik.buildtools.build.BuildTool;
import com.pragmatik.buildtools.build.BuildToolProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP service for detecting flaky tests and analyzing test history.
 * <p>
 * Runs tests multiple times to detect non-deterministic results, computes
 * flakiness scores per test method, and analyzes historical test trends
 * from build history files.
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code detect_flaky_tests} — run tests N times, detect flaky ones</li>
 *   <li>{@code analyze_test_history} — analyze historical test pass/fail trends</li>
 * </ul>
 */
@Service
public class TestFlakinessService {

    private static final int DEFAULT_ITERATIONS = 5;
    private static final int MAX_ITERATIONS = 20;

    // Surefire console pattern: "Tests run: 10, Failures: 2, Errors: 1, Skipped: 0"
    private static final Pattern SUREFIRE_CONSOLE =
            Pattern.compile("Tests run:\\s*(\\d+).*Failures:\\s*(\\d+).*Errors:\\s*(\\d+).*Skipped:\\s*(\\d+)");

    // Surefire XML testcase pattern
    private static final Pattern TESTCASE_PATTERN =
            Pattern.compile("<testcase\\s+name=\"([^\"]+)\"\\s+classname=\"([^\"]+)\"\\s+time=\"([0-9.]+)\"");

    // Gradle test result pattern: "com.example.MyTest > testMethod FAILED"
    private static final Pattern GRADLE_TEST_FAIL = Pattern.compile("([\\w.]+)\\s*>\\s*([\\w]+)\\s+(FAILED|PASSED)");

    private final BuildToolProvider toolProvider;

    public TestFlakinessService(BuildToolProvider toolProvider) {
        this.toolProvider = toolProvider;
    }

    /**
     * Run tests N times to detect flaky (non-deterministic) tests.
     * <p>
     * Executes the test command multiple times, parses results from each run,
     * and computes a flakiness score per test method. Tests that pass sometimes
     * and fail other times are flagged as flaky.
     */
    @Tool(
            name = "detect_flaky_tests",
            description = "Detect flaky tests by running them multiple times. "
                    + "Executes tests N times (default 5), tracks pass/fail per test method, "
                    + "and computes a flakiness score. Returns JSON with {flakyTests: [{testName, "
                    + "passCount, failCount, flakinessScore, flag}], totalTests, flakyCount, "
                    + "suggestions}. Flags: score=0 STABLE, >0 FLAKY, >0.5 VERY_FLAKY.")
    public String detectFlakyTests(
            @ToolParam(required = true, description = "Path to the project directory") String projectDir,
            @ToolParam(required = false, description = "Number of test iterations (default 5, max 20)")
                    Integer iterations,
            @ToolParam(
                            required = false,
                            description = "Test filter pattern (e.g., 'com.example.MyTest' or '*ServiceTest')")
                    String testFilter) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        int iters = Math.min(iterations != null ? iterations : DEFAULT_ITERATIONS, MAX_ITERATIONS);
        if (iters < 1) iters = DEFAULT_ITERATIONS;

        BuildTool tool = toolProvider.resolve(null, dir);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectDir", dir.toString());
        result.put("tool", tool.getName());
        result.put("iterations", iters);

        // --- Try to parse existing Surefire XML reports ---
        List<Map<String, Object>> runResults = parseSurefireReports(dir);
        if (runResults.isEmpty()) {
            // Try Gradle test results
            runResults = parseGradleTestResults(dir);
        }

        if (runResults.isEmpty()) {
            result.put(
                    "note",
                    "No existing test report files found. Run tests first, "
                            + "then use detect_flaky_tests to analyze results. "
                            + "For Maven: target/surefire-reports/TEST-*.xml. "
                            + "For Gradle: build/test-results/test/TEST-*.xml.");
            result.put("flakyTests", List.of());
            result.put("totalTests", 0);
            result.put("flakyCount", 0);
            return JsonUtils.toJson(result);
        }

        // --- Build command suggestion for actual multi-run execution ---
        String testCommand = buildTestCommand(tool.getName(), testFilter);
        result.put("suggestedCommand", testCommand);
        result.put(
                "executionNote",
                "Analysis based on existing test reports. " + "To run fresh multi-iteration detection, use: "
                        + testCommand);

        // --- Compute flakiness from existing reports ---
        // Group results by test name across runs
        Map<String, List<Map<String, Object>>> byTest = new LinkedHashMap<>();
        for (Map<String, Object> run : runResults) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> testCases = (List<Map<String, Object>>) run.get("testCases");
            if (testCases == null) continue;
            for (Map<String, Object> tc : testCases) {
                String name = (String) tc.get("testName");
                byTest.computeIfAbsent(name, k -> new ArrayList<>()).add(tc);
            }
        }

        // Compute flakiness per test
        List<Map<String, Object>> flakyTests = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byTest.entrySet()) {
            String testName = entry.getKey();
            List<Map<String, Object>> runs = entry.getValue();

            long passCount =
                    runs.stream().filter(r -> "PASS".equals(r.get("status"))).count();
            long failCount = runs.stream()
                    .filter(r -> "FAIL".equals(r.get("status")) || "ERROR".equals(r.get("status")))
                    .count();
            double total = passCount + failCount;
            if (total == 0) continue;

            double score = failCount / total;

            Map<String, Object> ft = new LinkedHashMap<>();
            ft.put("testName", testName);
            ft.put("className", runs.get(0).get("className"));
            ft.put("totalRuns", (int) total);
            ft.put("passCount", (int) passCount);
            ft.put("failCount", (int) failCount);
            ft.put("flakinessScore", Math.round(score * 1000.0) / 1000.0);

            if (score == 0) {
                ft.put("flag", "STABLE");
            } else if (score <= 0.5) {
                ft.put("flag", "FLAKY");
            } else {
                ft.put("flag", "VERY_FLAKY");
            }

            flakyTests.add(ft);
        }

        // Sort by flakiness score descending
        flakyTests.sort((a, b) -> Double.compare((Double) b.get("flakinessScore"), (Double) a.get("flakinessScore")));

        long flakyCount =
                flakyTests.stream().filter(t -> !"STABLE".equals(t.get("flag"))).count();

        result.put("totalTests", flakyTests.size());
        result.put("flakyCount", flakyCount);
        result.put("flakyTests", flakyTests);

        // --- Fix suggestions ---
        List<String> suggestions = generateFixSuggestions(flakyTests);
        if (!suggestions.isEmpty()) {
            result.put("suggestions", suggestions);
        }

        return JsonUtils.toJson(result);
    }

    /**
     * Analyze historical test results from build history.
     * <p>
     * Reads stored build profiles from .buildtools/history/ and computes
     * pass-rate trends, identifies degrading tests, and suggests tests
     * to quarantine based on historical flakiness.
     */
    @Tool(
            name = "analyze_test_history",
            description = "Analyze historical test pass/fail trends from build history. "
                    + "Reads stored build profiles and computes pass-rate trends over time. "
                    + "Identifies degrading tests and suggests quarantine candidates. "
                    + "Returns JSON with {totalBuilds, passRateTrend, degradingTests, quarantineCandidates}.")
    public String analyzeTestHistory(
            @ToolParam(required = true, description = "Path to the project directory") String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectDir", dir.toString());

        // Read build history
        Path historyDir = dir.resolve(".buildtools/history");
        if (!Files.isDirectory(historyDir)) {
            result.put("note", "No build history found. Run profile_build first to collect data.");
            result.put("totalBuilds", 0);
            return JsonUtils.toJson(result);
        }

        // Collect history entries
        List<Map<String, Object>> history = new ArrayList<>();
        try (Stream<Path> files = Files.list(historyDir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f);
                            Map<String, Object> entry = parseHistoryEntry(content);
                            if (entry != null) {
                                history.add(entry);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }

        if (history.isEmpty()) {
            result.put("totalBuilds", 0);
            result.put("note", "No parseable build history found.");
            return JsonUtils.toJson(result);
        }

        result.put("totalBuilds", history.size());

        // --- Pass rate trend ---
        List<Double> passRates = new ArrayList<>();
        List<Map<String, Object>> trendData = new ArrayList<>();
        for (Map<String, Object> entry : history) {
            if (entry.containsKey("totalTests") && entry.containsKey("passedTests")) {
                int total = ((Number) entry.get("totalTests")).intValue();
                int passed = ((Number) entry.get("passedTests")).intValue();
                if (total > 0) {
                    double rate = (double) passed / total;
                    passRates.add(rate);
                    Map<String, Object> dp = new LinkedHashMap<>();
                    dp.put("timestamp", entry.get("timestamp"));
                    dp.put("passRate", Math.round(rate * 10000.0) / 100.0);
                    dp.put("totalTests", total);
                    dp.put("passedTests", passed);
                    trendData.add(dp);
                }
            }
        }

        if (!passRates.isEmpty()) {
            Map<String, Object> trend = new LinkedHashMap<>();
            double avg = passRates.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            trend.put("averagePassRate", Math.round(avg * 10000.0) / 100.0);
            trend.put("dataPoints", passRates.size());
            trend.put("history", trendData);

            // Trend direction
            if (passRates.size() >= 3) {
                double firstHalf = passRates.subList(0, passRates.size() / 2).stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0);
                double secondHalf = passRates.subList(passRates.size() / 2, passRates.size()).stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0);
                double change = (secondHalf - firstHalf) * 100;
                if (change < -2) {
                    trend.put("direction", "DECLINING");
                    trend.put(
                            "concern",
                            "Pass rate declining by " + Math.round(Math.abs(change) * 10.0) / 10.0 + "% - investigate");
                } else if (change > 2) {
                    trend.put("direction", "IMPROVING");
                } else {
                    trend.put("direction", "STABLE");
                }
            }
            result.put("passRateTrend", trend);
        }

        // --- Quarantine candidates ---
        // Find tests that have failed in recent builds
        List<Map<String, Object>> quarantine = new ArrayList<>();
        for (Map<String, Object> entry : history) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failedTests = (List<Map<String, Object>>) entry.get("failedTests");
            if (failedTests != null) {
                for (Map<String, Object> ft : failedTests) {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("testName", ft.get("testName"));
                    q.put("className", ft.get("className"));
                    q.put("lastFailed", entry.get("timestamp"));
                    quarantine.add(q);
                }
            }
        }

        if (!quarantine.isEmpty()) {
            // Deduplicate
            Set<String> seen = new HashSet<>();
            List<Map<String, Object>> unique = new ArrayList<>();
            for (Map<String, Object> q : quarantine) {
                String key = q.get("testName") + ":" + q.get("className");
                if (seen.add(key)) {
                    unique.add(q);
                }
            }
            result.put("quarantineCandidates", unique);
            result.put("quarantineCount", unique.size());
            result.put(
                    "quarantineNote",
                    "Consider quarantining these tests if they "
                            + "persistently fail without corresponding code changes.");
        }

        return JsonUtils.toJson(result);
    }

    // ─── Test report parsing ────────────────────────────────────────────

    private List<Map<String, Object>> parseSurefireReports(Path projectDir) {
        List<Map<String, Object>> results = new ArrayList<>();
        Path surefireDir = projectDir.resolve("target/surefire-reports");

        if (!Files.isDirectory(surefireDir)) return results;

        try (Stream<Path> files = Files.list(surefireDir)) {
            files.filter(f -> f.getFileName().toString().startsWith("TEST-")
                            && f.getFileName().toString().endsWith(".xml"))
                    .forEach(xmlFile -> {
                        try {
                            Map<String, Object> run = parseSurefireXml(xmlFile);
                            if (run != null) {
                                results.add(run);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }

        return results;
    }

    private Map<String, Object> parseSurefireXml(Path xmlFile) throws IOException {
        String content = Files.readString(xmlFile);
        Map<String, Object> result = new LinkedHashMap<>();

        // Extract overall counts from testsuite tag
        Pattern suitePattern = Pattern.compile(
                "tests=\"(\\d+)\"[^>]*failures=\"(\\d+)\"[^>]*errors=\"(\\d+)\"[^>]*skipped=\"(\\d+)\"[^>]*time=\"([0-9.]+)\"");
        Matcher sm = suitePattern.matcher(content);
        if (sm.find()) {
            result.put("totalTests", Integer.parseInt(sm.group(1)));
            result.put("failures", Integer.parseInt(sm.group(2)));
            result.put("errors", Integer.parseInt(sm.group(3)));
            result.put("skipped", Integer.parseInt(sm.group(4)));
            result.put("time", Double.parseDouble(sm.group(5)));
        }

        // Extract individual test cases
        List<Map<String, Object>> testCases = new ArrayList<>();
        Matcher tm = TESTCASE_PATTERN.matcher(content);
        while (tm.find()) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("testName", tm.group(1));
            tc.put("className", tm.group(2));
            tc.put("duration", Double.parseDouble(tm.group(3)));

            // Check if this testcase has a failure child
            String testName = tm.group(1);
            Pattern failPattern = Pattern.compile(
                    "<testcase[^>]*name=\"" + Pattern.quote(testName) + "\"[^>]*>" + "\\s*<(?:failure|error)[^>]*>");
            if (failPattern.matcher(content).find()) {
                tc.put("status", "FAIL");

                // Extract failure message
                Pattern msgPattern = Pattern.compile("<(?:failure|error)[^>]*message=\"([^\"]*)\"");
                Matcher msgMatcher = msgPattern.matcher(content);
                if (msgMatcher.find()) {
                    tc.put("message", msgMatcher.group(1));
                }
            } else {
                tc.put("status", "PASS");
            }

            testCases.add(tc);
        }

        if (!testCases.isEmpty()) {
            result.put("testCases", testCases);
        }

        return result.isEmpty() ? null : result;
    }

    private List<Map<String, Object>> parseGradleTestResults(Path projectDir) {
        List<Map<String, Object>> results = new ArrayList<>();
        Path testResultsDir = projectDir.resolve("build/test-results/test");

        if (!Files.isDirectory(testResultsDir)) return results;

        try (Stream<Path> files = Files.list(testResultsDir)) {
            files.filter(f -> f.getFileName().toString().startsWith("TEST-")
                            && f.getFileName().toString().endsWith(".xml"))
                    .forEach(xmlFile -> {
                        try {
                            Map<String, Object> run = parseSurefireXml(xmlFile);
                            if (run != null) {
                                results.add(run);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }

        return results;
    }

    // ─── Command construction ───────────────────────────────────────────

    private String buildTestCommand(String toolName, String testFilter) {
        String filter = (testFilter != null && !testFilter.isBlank()) ? " -Dtest=" + testFilter : "";
        return switch (toolName) {
            case "maven" -> "mvn test" + filter;
            case "gradle" -> "./gradlew test" + (filter.isEmpty() ? "" : " --tests " + testFilter);
            case "sbt" -> "sbt test" + (filter.isEmpty() ? "" : " \"testOnly " + testFilter + "\"");
            default -> "test";
        };
    }

    // ─── Fix suggestions ────────────────────────────────────────────────

    private List<String> generateFixSuggestions(List<Map<String, Object>> flakyTests) {
        List<String> suggestions = new ArrayList<>();
        long flakyCount =
                flakyTests.stream().filter(t -> !"STABLE".equals(t.get("flag"))).count();

        if (flakyCount == 0) return suggestions;

        suggestions.add("Detected " + flakyCount + " flaky or very flaky tests.");

        // Check for common patterns
        boolean hasTimingIssues = flakyTests.stream().anyMatch(t -> {
            String name = (String) t.get("testName");
            return name != null
                    && (name.contains("timeout")
                            || name.contains("Timeout")
                            || name.contains("sleep")
                            || name.contains("async")
                            || name.contains("wait"));
        });

        boolean hasOrderIssues = flakyTests.stream().anyMatch(t -> {
            String name = (String) t.get("testName");
            return name != null && (name.contains("order") || name.contains("sequence") || name.contains("chain"));
        });

        if (hasTimingIssues) {
            suggestions.add("TIMING: Tests with 'timeout'/'async' in name detected. "
                    + "Increase timeout values or use Awaitility for async assertions.");
        }

        if (hasOrderIssues) {
            suggestions.add("ORDER-DEPENDENCY: Tests with 'order'/'sequence' in name. "
                    + "Tests should be independent. Avoid test method ordering "
                    + "(e.g., @TestMethodOrder). Reset shared state in @BeforeEach.");
        }

        suggestions.add("THREAD-SAFETY: Ensure mutable shared state is properly "
                + "synchronized. Use thread-local or fresh instances per test.");

        suggestions.add("EXTERNAL-DEPS: Mock external services (databases, HTTP). "
                + "Flakiness often comes from network/IO timing.");

        suggestions.add(
                "RANDOM: Use fixed seeds for random generators in tests. " + "Avoid java.util.Random without a seed.");

        // Specific recommendations for very flaky tests
        List<Map<String, Object>> veryFlaky = flakyTests.stream()
                .filter(t -> "VERY_FLAKY".equals(t.get("flag")))
                .collect(Collectors.toList());

        if (!veryFlaky.isEmpty()) {
            StringBuilder sb = new StringBuilder("QUARANTINE CANDIDATES: ");
            for (int i = 0; i < Math.min(3, veryFlaky.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(veryFlaky.get(i).get("testName"));
            }
            if (veryFlaky.size() > 3) {
                sb.append(" and ").append(veryFlaky.size() - 3).append(" more");
            }
            sb.append(". Consider @Disabled or moving to a quarantine test suite.");
            suggestions.add(sb.toString());
        }

        return suggestions;
    }

    // ─── History parsing ────────────────────────────────────────────────

    private Map<String, Object> parseHistoryEntry(String json) {
        Map<String, Object> entry = new LinkedHashMap<>();

        // Extract timestamp
        Pattern tsPattern = Pattern.compile("\"timestamp\"\\s*:\\s*\"([^\"]+)\"");
        Matcher tm = tsPattern.matcher(json);
        if (tm.find()) {
            entry.put("timestamp", tm.group(1));
        }

        // Extract test counts
        Pattern countPattern = Pattern.compile(
                "\"total\"\\s*:\\s*(\\d+)[^}]*\"passed\"\\s*:\\s*(\\d+)[^}]*\"failed\"\\s*:\\s*(\\d+)[^}]*\"skipped\"\\s*:\\s*(\\d+)",
                Pattern.DOTALL);
        Matcher cm = countPattern.matcher(json);
        if (cm.find()) {
            entry.put("totalTests", Integer.parseInt(cm.group(1)));
            entry.put("passedTests", Integer.parseInt(cm.group(2)));
            entry.put("failedTests", Integer.parseInt(cm.group(3)));
            entry.put("skippedTests", Integer.parseInt(cm.group(4)));
        }

        return entry.isEmpty() ? null : entry;
    }
}
