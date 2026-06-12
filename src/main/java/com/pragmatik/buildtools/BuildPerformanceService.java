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

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP service for analyzing build performance and suggesting optimizations.
 * <p>
 * Provides tools for:
 * <ul>
 *   <li>Profiling individual build runs (timing, phase breakdown)</li>
 *   <li>Analyzing historical build trends (are builds getting slower?)</li>
 *   <li>Suggesting build performance optimizations</li>
 *   <li>Measuring cold vs warm build times</li>
 * </ul>
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code profile_build} — run a build with timing instrumentation</li>
 *   <li>{@code analyze_build_performance} — analyze build performance and suggest optimizations</li>
 * </ul>
 */
@Service
public class BuildPerformanceService {

    private static final Pattern MAVEN_TIME_PATTERN = Pattern.compile(
            "Total time:\\s+([0-9]+(?:\\.[0-9]+)?)\\s*s");

    private static final Pattern GRADLE_TIME_PATTERN = Pattern.compile(
            "BUILD (?:SUCCESSFUL|FAILED) in ([0-9]+(?:\\.[0-9]+)?)s");

    private static final Pattern MAVEN_PHASE_PATTERN = Pattern.compile(
            "\\[INFO\\] --- ([a-zA-Z0-9._-]+):([0-9.]+):([a-zA-Z-]+)\\s.*---");

    private static final Pattern SBT_TIME_PATTERN = Pattern.compile(
            "Total time:\\s*([0-9]+)\\s*s");

    private static final Pattern GRADLE_TASK_TIME = Pattern.compile(
            "(?:> Task|:)[\\w:]+\\s*(?:UP-TO-DATE|SKIPPED|FAILED)?\\s*");

    private final BuildToolProvider toolProvider;

    public BuildPerformanceService(BuildToolProvider toolProvider) {
        this.toolProvider = toolProvider;
    }

    /**
     * Run a build command with timing instrumentation and return performance metrics.
     */
    @Tool(name = "profile_build",
          description = "Execute a build command with timing instrumentation. " +
                        "Returns detailed performance metrics including total duration, " +
                        "phase/task breakdown (for Maven/Gradle), and comparison against " +
                        "previous builds. Use this to identify slow build phases and " +
                        "track build performance over time. " +
                        "Returns JSON with {success, tool, command, duration, durationFormatted, " +
                        "phases: [{name, duration}], comparison: {trend, previousDurations}}.")
    public String profileBuild(
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
            @ToolParam(required = false, description = "Build tool name. Omit to auto-detect.")
            String buildToolName,
            @ToolParam(required = false, description = "Path to build tool installation.")
            String buildToolHome,
            @ToolParam(required = true, description = "Path to the project directory")
            String projectDir,
            @ToolParam(required = true, description = "Build command to profile")
            String command) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        String validatedHome = null;
        if (buildToolHome != null && !buildToolHome.isBlank()) {
            try {
                validatedHome = Path.of(buildToolHome).toRealPath().toString();
            } catch (IOException e) {
                return JsonUtils.errorJson("Cannot resolve build tool home: " + buildToolHome);
            }
        }

        BuildTool tool = toolProvider.resolve(buildToolName, dir);
        Map<String, Object> result = new LinkedHashMap<>();

        // Time the build
        Instant start = Instant.now();
        String rawOutput;
        int exitCode;
        try {
            rawOutput = tool.executeCommand(validatedHome, dir.toString(), command);
            exitCode = 0;
        } catch (RuntimeException e) {
            rawOutput = e.getMessage();
            exitCode = 1;
        }
        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);

        result.put("tool", tool.getName());
        result.put("command", command);
        result.put("projectDir", dir.toString());
        result.put("success", exitCode == 0);
        result.put("timestamp", start.toString());

        // Duration
        double totalSeconds = duration.toMillis() / 1000.0;
        result.put("durationSeconds", Math.round(totalSeconds * 1000.0) / 1000.0);
        result.put("durationFormatted", formatDuration(duration));

        // Parse tool-reported time for comparison
        Double toolReportedTime = extractToolReportedTime(rawOutput, tool.getName());
        if (toolReportedTime != null) {
            result.put("toolReportedSeconds", toolReportedTime);
            result.put("overheadSeconds", Math.round((totalSeconds - toolReportedTime) * 1000.0) / 1000.0);
        }

        // Phase breakdown
        List<Map<String, Object>> phases = extractPhases(rawOutput, tool.getName(), duration);
        if (!phases.isEmpty()) {
            result.put("phaseCount", phases.size());
            result.put("phases", phases);

            // Find slowest phase
            phases.stream()
                    .filter(p -> p.containsKey("durationSeconds"))
                    .max(Comparator.comparingDouble(p ->
                            ((Number) p.get("durationSeconds")).doubleValue()))
                    .ifPresent(slowest -> result.put("slowestPhase", slowest));
        }

        // Test count extraction
        Map<String, Integer> testCounts = extractTestCounts(rawOutput, tool.getName());
        if (!testCounts.isEmpty()) {
            result.put("testSummary", testCounts);
        }

        // Persist build record and compare with history
        List<Map<String, Object>> history = loadBuildHistory(dir, tool.getName(), command);
        history.add(Map.of(
                "timestamp", start.toString(),
                "durationSeconds", Math.round(totalSeconds * 1000.0) / 1000.0,
                "success", exitCode == 0,
                "phases", phases.size()
        ));

        // Trim to last 20 builds
        if (history.size() > 20) {
            history = new ArrayList<>(history.subList(history.size() - 20, history.size()));
        }

        try {
            saveBuildHistory(dir, tool.getName(), command, history);
        } catch (IOException ignored) {
            // Non-critical — just skip persistence
        }

        // Trend analysis
        if (history.size() >= 2) {
            Map<String, Object> comparison = analyzeTrend(history);
            result.put("comparison", comparison);
        }

        // Optimization suggestions
        List<String> suggestions = generateSuggestions(tool.getName(), command,
                totalSeconds, phases, testCounts);
        if (!suggestions.isEmpty()) {
            result.put("suggestions", suggestions);
        }

        return JsonUtils.toJson(result);
    }

    /**
     * Analyze build performance without executing a build.
     * Looks at historical data and build configuration to suggest optimizations.
     */
    @Tool(name = "analyze_build_performance",
          description = "Analyze build performance from historical data and configuration. " +
                        "Examines build files and past build profiles to suggest optimizations " +
                        "like parallel builds, build caching, incremental compilation, and daemon usage. " +
                        "Does NOT execute any builds — read-only analysis. " +
                        "Returns JSON with {suggestions, buildToolPattern, optimizationPotential}.")
    public String analyzeBuildPerformance(
            @ToolParam(required = true, description = "Path to the project directory")
            String projectDir,
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
            @ToolParam(required = false, description = "Build tool to analyze. Omit to auto-detect.")
            String buildToolName) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        BuildTool tool = toolProvider.resolve(buildToolName, dir);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", tool.getName());
        result.put("projectDir", dir.toString());

        List<String> suggestions = new ArrayList<>();

        // Load history for all commands
        Map<String, List<Map<String, Object>>> allHistory = new LinkedHashMap<>();
        try {
            loadAllBuildHistory(dir, tool.getName(), allHistory);
        } catch (IOException ignored) {}

        // Analyze historical patterns
        if (!allHistory.isEmpty()) {
            int totalBuilds = allHistory.values().stream().mapToInt(List::size).sum();
            result.put("totalTrackedBuilds", totalBuilds);

            // Detect trend
            List<String> trendInsights = analyzeAllTrends(allHistory);
            suggestions.addAll(trendInsights);
        }

        // Tool-specific analysis
        switch (tool.getName()) {
            case "maven":
                suggestions.addAll(analyzeMavenPerformance(dir));
                break;
            case "gradle":
                suggestions.addAll(analyzeGradlePerformance(dir));
                break;
            case "sbt":
                suggestions.addAll(analyzeSbtPerformance(dir));
                break;
        }

        // General suggestions
        suggestions.addAll(generalSuggestions(tool.getName()));

        result.put("suggestionCount", suggestions.size());
        result.put("suggestions", suggestions);

        // Optimization potential estimate
        Map<String, Object> potential = new LinkedHashMap<>();
        potential.put("level", suggestions.size() >= 4 ? "HIGH" :
                suggestions.size() >= 2 ? "MEDIUM" : "LOW");
        potential.put("estimatedImprovement", suggestions.size() >= 4 ?
                "30-60% reduction possible" : suggestions.size() >= 2 ?
                "15-30% reduction possible" : "Minimal optimization headroom");
        result.put("optimizationPotential", potential);

        return JsonUtils.toJson(result);
    }

    // ─── Time extraction ────────────────────────────────────────────────

    private Double extractToolReportedTime(String output, String toolName) {
        Pattern pattern = switch (toolName) {
            case "maven" -> MAVEN_TIME_PATTERN;
            case "gradle" -> GRADLE_TIME_PATTERN;
            case "sbt" -> SBT_TIME_PATTERN;
            default -> null;
        };
        if (pattern == null) return null;

        Matcher m = pattern.matcher(output);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // ─── Phase extraction ───────────────────────────────────────────────

    private List<Map<String, Object>> extractPhases(String output, String toolName, Duration totalDuration) {
        List<Map<String, Object>> phases = new ArrayList<>();

        if ("maven".equals(toolName)) {
            Matcher m = MAVEN_PHASE_PATTERN.matcher(output);
            while (m.find()) {
                Map<String, Object> phase = new LinkedHashMap<>();
                phase.put("plugin", m.group(1));
                phase.put("version", m.group(2));
                phase.put("goal", m.group(3));
                phase.put("name", m.group(1) + ":" + m.group(3));
                phases.add(phase);
            }
            // Distribute time estimate across phases
            if (!phases.isEmpty() && totalDuration != null) {
                double perPhase = (totalDuration.toMillis() / 1000.0) / phases.size();
                for (Map<String, Object> p : phases) {
                    p.put("durationSeconds", Math.round(perPhase * 1000.0) / 1000.0);
                    p.put("estimated", true);
                }
            }
        }

        return phases;
    }

    // ─── Test count extraction ──────────────────────────────────────────

    private Map<String, Integer> extractTestCounts(String output, String toolName) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        if ("maven".equals(toolName) || "gradle".equals(toolName)) {
            Pattern tp = Pattern.compile("Tests run:\\s*(\\d+).*Failures:\\s*(\\d+).*Errors:\\s*(\\d+).*Skipped:\\s*(\\d+)");
            Matcher m = tp.matcher(output);
            if (m.find()) {
                counts.put("total", parseInt(m.group(1)));
                counts.put("failed", parseInt(m.group(2)));
                counts.put("errors", parseInt(m.group(3)));
                counts.put("skipped", parseInt(m.group(4)));
            }
        }

        return counts;
    }

    // ─── History persistence ────────────────────────────────────────────

    private List<Map<String, Object>> loadBuildHistory(Path projectDir, String tool,
                                                        String command) throws IOException {
        Path historyDir = projectDir.resolve(".buildtools/history");
        if (!Files.exists(historyDir)) return new ArrayList<>();

        String filename = sanitizeFilename(tool + "_" + command) + ".json";
        Path historyFile = historyDir.resolve(filename);
        if (!Files.exists(historyFile)) return new ArrayList<>();

        // Simple JSON-like parsing
        String content = Files.readString(historyFile);
        return parseHistoryJson(content);
    }

    private void loadAllBuildHistory(Path projectDir, String tool,
                                      Map<String, List<Map<String, Object>>> all) throws IOException {
        Path historyDir = projectDir.resolve(".buildtools/history");
        if (!Files.exists(historyDir)) return;

        try (var stream = Files.list(historyDir)) {
            stream.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f);
                            String cmd = f.getFileName().toString().replace(".json", "")
                                    .replace(tool + "_", "");
                            all.put(cmd, parseHistoryJson(content));
                        } catch (IOException ignored) {}
                    });
        }
    }

    private void saveBuildHistory(Path projectDir, String tool, String command,
                                   List<Map<String, Object>> history) throws IOException {
        Path historyDir = projectDir.resolve(".buildtools/history");
        Files.createDirectories(historyDir);

        String filename = sanitizeFilename(tool + "_" + command) + ".json";
        Path historyFile = historyDir.resolve(filename);

        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < history.size(); i++) {
            Map<String, Object> entry = history.get(i);
            json.append("  {");
            boolean first = true;
            for (Map.Entry<String, Object> e : entry.entrySet()) {
                if (!first) json.append(", ");
                first = false;
                json.append("\"").append(esc(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v instanceof Number) json.append(v);
                else if (v instanceof Boolean) json.append(v);
                else json.append("\"").append(esc(String.valueOf(v))).append("\"");
            }
            json.append("}");
            if (i < history.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("]");

        Files.writeString(historyFile, json.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseHistoryJson(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        // Simple manual parsing for our known format
        Pattern entryPattern = Pattern.compile(
                "\\{\"timestamp\":\"([^\"]+)\",\"durationSeconds\":([0-9.]+),\"success\":(true|false),\"phases\":(\\d+)\\}");
        Matcher m = entryPattern.matcher(json);
        while (m.find()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("timestamp", m.group(1));
            entry.put("durationSeconds", Double.parseDouble(m.group(2)));
            entry.put("success", Boolean.parseBoolean(m.group(3)));
            entry.put("phases", Integer.parseInt(m.group(4)));
            result.add(entry);
        }
        return result;
    }

    // ─── Trend analysis ─────────────────────────────────────────────────

    private Map<String, Object> analyzeTrend(List<Map<String, Object>> history) {
        Map<String, Object> comparison = new LinkedHashMap<>();

        // Average of last 5 vs average of earlier builds
        int split = Math.max(1, history.size() - 5);
        List<Map<String, Object>> recent = history.subList(split, history.size());
        List<Map<String, Object>> earlier = history.subList(0, split);

        double recentAvg = recent.stream()
                .mapToDouble(e -> ((Number) e.get("durationSeconds")).doubleValue())
                .average().orElse(0);
        double earlierAvg = earlier.stream()
                .mapToDouble(e -> ((Number) e.get("durationSeconds")).doubleValue())
                .average().orElse(0);

        comparison.put("recentAvgSeconds", Math.round(recentAvg * 1000.0) / 1000.0);
        comparison.put("earlierAvgSeconds", Math.round(earlierAvg * 1000.0) / 1000.0);
        comparison.put("buildsTracked", history.size());

        if (earlierAvg > 0) {
            double change = ((recentAvg - earlierAvg) / earlierAvg) * 100;
            comparison.put("changePercent", Math.round(change * 10.0) / 10.0);
            if (change > 10) {
                comparison.put("trend", "SLOWER — builds are taking longer recently");
                comparison.put("alert", "Build times increased by " +
                        Math.round(change * 10.0) / 10.0 + "% — investigate potential causes");
            } else if (change < -10) {
                comparison.put("trend", "FASTER — builds are improving");
            } else {
                comparison.put("trend", "STABLE — build times are consistent");
            }
        } else {
            comparison.put("trend", "INSUFFICIENT_DATA");
        }

        // List recent durations
        List<Double> recentDurations = recent.stream()
                .map(e -> ((Number) e.get("durationSeconds")).doubleValue())
                .collect(Collectors.toList());
        comparison.put("recentDurations", recentDurations);

        return comparison;
    }

    private List<String> analyzeAllTrends(Map<String, List<Map<String, Object>>> allHistory) {
        List<String> insights = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : allHistory.entrySet()) {
            if (entry.getValue().size() >= 5) {
                Map<String, Object> trend = analyzeTrend(entry.getValue());
                String trendVal = (String) trend.get("trend");
                if ("SLOWER — builds are taking longer recently".equals(trendVal)) {
                    insights.add("Command '" + entry.getKey() + "' is trending slower (" +
                            trend.get("changePercent") + "% increase). Review for build cache issues.");
                }
            }
        }
        return insights;
    }

    // ─── Performance suggestions ────────────────────────────────────────

    private List<String> generateSuggestions(String tool, String command,
                                              double durationSeconds,
                                              List<Map<String, Object>> phases,
                                              Map<String, Integer> testCounts) {
        List<String> suggestions = new ArrayList<>();

        // Duration-based suggestions
        if (durationSeconds > 60) {
            suggestions.add("Build took " + formatDuration(Duration.ofMillis((long)(durationSeconds * 1000))) +
                    ". Consider enabling parallel execution to reduce build time.");
        }

        // Tool-specific
        switch (tool) {
            case "maven":
                if (command.contains("test") && durationSeconds > 30) {
                    suggestions.add("Use -T1C (one thread per CPU core) to parallelize Maven builds.");
                    if (!command.contains("-T")) {
                        suggestions.add("Add -T4 flag to use 4 threads for this build.");
                    }
                }
                if (command.contains("clean") && !command.contains("install") && !command.contains("deploy")) {
                    suggestions.add("Skip clean unless necessary — incremental builds are faster.");
                }
                break;
            case "gradle":
                if (!command.contains("--parallel")) {
                    suggestions.add("Add --parallel flag to enable parallel project execution.");
                }
                if (!command.contains("--build-cache")) {
                    suggestions.add("Add --build-cache to reuse outputs from previous builds.");
                }
                if (!command.contains("--daemon")) {
                    suggestions.add("Ensure Gradle daemon is running (--daemon) to avoid JVM startup cost.");
                }
                break;
            case "sbt":
                suggestions.add("Use 'sbt shell' for interactive development — avoids JVM startup on every command.");
                if (command.contains("test")) {
                    suggestions.add("Use 'testQuick' instead of 'test' for incremental test execution.");
                }
                break;
        }

        // Test-heavy builds
        if (testCounts.containsKey("total") && testCounts.get("total") > 50 && durationSeconds > 30) {
            suggestions.add("Large test suite (" + testCounts.get("total") + " tests). " +
                    "Consider parallel test execution to reduce test time.");
        }

        // Too many phases
        if (phases.size() > 10) {
            suggestions.add(phases.size() + " build phases detected. " +
                    "Consider splitting into focused goals to speed up iterative development.");
        }

        return suggestions;
    }

    private List<String> analyzeMavenPerformance(Path projectDir) {
        List<String> suggestions = new ArrayList<>();
        Path pomXml = projectDir.resolve("pom.xml");
        if (!Files.exists(pomXml)) return suggestions;

        try {
            String content = Files.readString(pomXml);

            // Check for parallel build configuration
            if (!content.contains("<fork>true</fork>")) {
                suggestions.add("Enable Maven surefire fork mode for isolated (and potentially parallel) test execution.");
            }

            // Check for build cache plugins
            if (!content.contains("maven-build-cache") && !content.contains("gradle-enterprise")) {
                suggestions.add("Consider adding a build cache plugin (maven-build-cache-extension or Gradle Enterprise) to reuse outputs across builds.");
            }

            // Check if skipping tests is configured
            if (content.contains("<skipTests>true</skipTests>")) {
                suggestions.add("Tests are currently skipped. Enable them periodically to verify correctness.");
            }
        } catch (IOException ignored) {}

        return suggestions;
    }

    private List<String> analyzeGradlePerformance(Path projectDir) {
        List<String> suggestions = new ArrayList<>();
        // Check gradle.properties
        Path props = projectDir.resolve("gradle.properties");
        if (Files.exists(props)) {
            try {
                List<String> lines = Files.readAllLines(props);
                boolean hasParallel = lines.stream().anyMatch(l ->
                        l.trim().startsWith("org.gradle.parallel="));
                boolean hasCache = lines.stream().anyMatch(l ->
                        l.trim().startsWith("org.gradle.caching="));
                boolean hasDaemon = lines.stream().anyMatch(l ->
                        l.trim().startsWith("org.gradle.daemon="));
                boolean hasConfigCache = lines.stream().anyMatch(l ->
                        l.trim().startsWith("org.gradle.configuration-cache="));

                if (!hasParallel) {
                    suggestions.add("Add org.gradle.parallel=true to gradle.properties for parallel project builds.");
                }
                if (!hasCache) {
                    suggestions.add("Add org.gradle.caching=true to enable build cache in gradle.properties.");
                }
                if (!hasConfigCache) {
                    suggestions.add("Enable configuration cache: org.gradle.configuration-cache=true in gradle.properties.");
                }
            } catch (IOException ignored) {}
        } else {
            suggestions.add("No gradle.properties found. Create one with org.gradle.parallel=true and org.gradle.caching=true.");
        }

        return suggestions;
    }

    private List<String> analyzeSbtPerformance(Path projectDir) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("Use Coursier for faster dependency resolution (add to project/plugins.sbt: addSbtPlugin(\"io.get-coursier\" % \"sbt-coursier\" % \"...\"))");
        return suggestions;
    }

    private List<String> generalSuggestions(String tool) {
        List<String> suggestions = new ArrayList<>();

        suggestions.add("Use incremental compilation — most build tools support it by default but verify it's enabled.");
        suggestions.add("Review unnecessary plugin executions — each plugin adds overhead.");
        suggestions.add("Consider using build tool daemons (Maven mvnd, Gradle daemon) to avoid JVM warmup time.");

        if ("maven".equals(tool)) {
            suggestions.add("Try Maven Daemon (mvnd) for 2-3x faster builds compared to stock Maven.");
        }

        return suggestions;
    }

    // ─── Utilities ──────────────────────────────────────────────────────

    private String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m " + (s % 60) + "s";
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
