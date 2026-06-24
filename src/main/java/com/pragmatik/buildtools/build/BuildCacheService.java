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

import com.pragmatik.buildtools.tool.JsonUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP service for analyzing and optimizing build caching across JVM build tools.
 * <p>
 * Analyzes Gradle (config cache, build cache, task output caching),
 * SBT (execution log, incremental compilation, Coursier cache),
 * and Maven (mvnd, build cache extensions). Reports cache effectiveness
 * and generates optimization configuration snippets.
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code analyze_cache_health} — audit caching configuration and effectiveness</li>
 *   <li>{@code optimize_build_cache} — generate cache optimization configuration snippets</li>
 * </ul>
 */
@Service
public class BuildCacheService {

    // Gradle cache-related settings
    private static final Set<String> GRADLE_CACHE_PROPERTIES = Set.of(
            "org.gradle.caching",
            "org.gradle.parallel",
            "org.gradle.configureondemand",
            "org.gradle.configuration-cache",
            "org.gradle.configuration-cache.problems");

    // Maven cache-related extensions
    private static final Set<String> MAVEN_CACHE_EXTENSIONS =
            Set.of("maven-build-cache-extension", "takari-lifecycle", "gradle-enterprise-maven-extension");

    private final BuildToolProvider toolProvider;

    public BuildCacheService(BuildToolProvider toolProvider) {
        this.toolProvider = toolProvider;
    }

    /**
     * Analyze build cache health and effectiveness for a project.
     * <p>
     * Checks build tool configuration for caching settings, detects
     * cache-related plugins and extensions, and identifies optimization
     * opportunities. For Gradle, parses --info output for cache hit/miss
     * statistics when available.
     */
    @Tool(
            name = "analyze_cache_health",
            description = "Analyze build caching health across Maven, Gradle, and SBT projects. "
                    + "Checks configuration for cache settings, detects cache-related plugins, "
                    + "and identifies optimization opportunities. Returns JSON with "
                    + "{tool, cacheHealth: {status, findings, recommendations, score}}.")
    public String analyzeCacheHealth(
            @ToolParam(required = true, description = "Path to the project directory") String projectDir,
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
        result.put("projectDir", dir.toString());
        result.put("tool", tool.getName());

        Map<String, Object> cacheHealth = analyzeToolCache(dir, tool.getName());
        result.put("cacheHealth", cacheHealth);

        return JsonUtils.toJson(result);
    }

    /**
     * Generate cache optimization configuration snippets for a project.
     * <p>
     * Produces build-tool-specific configuration recommendations for
     * enabling and optimizing build caching, parallel execution,
     * incremental compilation, and daemon usage.
     */
    @Tool(
            name = "optimize_build_cache",
            description = "Generate cache optimization config snippets for a project. "
                    + "Produces build-tool-specific recommendations for caching, "
                    + "parallel execution, incremental compilation, and daemon usage. "
                    + "Returns JSON with {tool, optimizations: [{area, recommendation, config}]}.")
    public String optimizeBuildCache(
            @ToolParam(required = true, description = "Path to the project directory") String projectDir,
            @Schema(allowableValues = {"maven", "gradle", "sbt"})
                    @ToolParam(required = false, description = "Build tool. Omit to auto-detect.")
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
        result.put("projectDir", dir.toString());
        result.put("tool", tool.getName());

        List<Map<String, Object>> optimizations = generateOptimizations(dir, tool.getName());
        result.put("optimizationCount", optimizations.size());
        result.put("optimizations", optimizations);

        // Estimate improvement
        Map<String, Object> potential = new LinkedHashMap<>();
        int optCount = optimizations.size();
        if (optCount >= 5) {
            potential.put("level", "HIGH");
            potential.put("estimatedImprovement", "40-60% build time reduction possible");
        } else if (optCount >= 3) {
            potential.put("level", "MEDIUM");
            potential.put("estimatedImprovement", "20-40% build time reduction possible");
        } else if (optCount >= 1) {
            potential.put("level", "LOW");
            potential.put("estimatedImprovement", "10-20% build time reduction possible");
        } else {
            potential.put("level", "OPTIMAL");
            potential.put("estimatedImprovement", "Already well-optimized");
        }
        result.put("optimizationPotential", potential);

        return JsonUtils.toJson(result);
    }

    // ─── Cache health analysis per tool ─────────────────────────────────

    private Map<String, Object> analyzeToolCache(Path dir, String toolName) {
        return switch (toolName) {
            case "maven" -> analyzeMavenCache(dir);
            case "gradle" -> analyzeGradleCache(dir);
            case "sbt" -> analyzeSbtCache(dir);
            default -> Map.of("status", "UNKNOWN", "findings", List.of("Unsupported build tool: " + toolName));
        };
    }

    private Map<String, Object> analyzeMavenCache(Path dir) {
        Map<String, Object> health = new LinkedHashMap<>();
        List<String> findings = new ArrayList<>();
        int score = 0; // 0-100

        // Check for pom.xml
        Path pomXml = dir.resolve("pom.xml");
        if (!Files.exists(pomXml)) {
            health.put("status", "NO_PROJECT");
            findings.add("No pom.xml found. Not a Maven project.");
            health.put("findings", findings);
            health.put("score", 0);
            return health;
        }

        try {
            String pom = Files.readString(pomXml);

            // Check for Maven Daemon (mvnd)
            Path mvnw = dir.resolve("mvnw");
            if (Files.exists(mvnw)) {
                findings.add("mvnw wrapper detected — good for reproducible builds");
                score += 10;
            }

            // Check for build cache extensions in pom.xml
            boolean hasCacheExt = false;
            for (String ext : MAVEN_CACHE_EXTENSIONS) {
                if (pom.contains(ext)) {
                    findings.add("Build cache extension configured: " + ext);
                    hasCacheExt = true;
                    score += 30;
                    break;
                }
            }

            if (!hasCacheExt) {
                findings.add("No build cache extension detected. "
                        + "Consider maven-build-cache-extension for up to 90% faster incremental builds.");
            }

            // Check for parallel builds
            if (pom.contains("-T") || pom.contains("threadCount")) {
                findings.add("Parallel builds configured (good)");
                score += 20;
            } else {
                findings.add("Parallel builds not configured. Add -T1C to MAVEN_OPTS or use -T flag.");
            }

            // Check for Maven 4 features
            if (pom.contains("4.0.0") && (pom.contains("<mavenVersion>4") || pom.contains("maven.compiler.release"))) {
                findings.add("Using modern Maven features (Java release flag)");
                score += 10;
            }

            // Check for incremental compilation
            boolean hasCompilerPlugin = pom.contains("maven-compiler-plugin");
            if (hasCompilerPlugin) {
                if (pom.contains("useIncrementalCompilation") && pom.contains("true")) {
                    findings.add("Incremental compilation enabled (good)");
                    score += 15;
                } else {
                    findings.add("Incremental compilation not explicitly enabled. "
                            + "Configure <useIncrementalCompilation>true</useIncrementalCompilation>.");
                }
            }

            // Check .mvn directory for config
            Path mvnConfig = dir.resolve(".mvn/maven.config");
            if (Files.exists(mvnConfig)) {
                String config = Files.readString(mvnConfig);
                if (config.contains("-T")) {
                    score += 5;
                }
                if (config.contains("--no-transfer-progress")) {
                    score += 5;
                }
            }

        } catch (IOException ignored) {
        }

        // Status
        if (score >= 70) {
            health.put("status", "GOOD");
        } else if (score >= 40) {
            health.put("status", "ADEQUATE");
        } else {
            health.put("status", "NEEDS_ATTENTION");
        }

        health.put("score", score);
        health.put("findings", findings);
        return health;
    }

    private Map<String, Object> analyzeGradleCache(Path dir) {
        Map<String, Object> health = new LinkedHashMap<>();
        List<String> findings = new ArrayList<>();
        int score = 0;

        // Check gradle.properties
        Path gradleProps = dir.resolve("gradle.properties");
        Map<String, String> props = new LinkedHashMap<>();

        if (Files.exists(gradleProps)) {
            try {
                for (String line : Files.readAllLines(gradleProps)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        props.put(
                                line.substring(0, eq).trim(),
                                line.substring(eq + 1).trim());
                    }
                }
            } catch (IOException ignored) {
            }
        }

        // Build cache
        if ("true".equalsIgnoreCase(props.get("org.gradle.caching"))) {
            findings.add("Build cache enabled (good)");
            score += 25;
        } else {
            findings.add("Build cache not enabled. Add org.gradle.caching=true to gradle.properties.");
        }

        // Configuration cache
        if ("true".equalsIgnoreCase(props.get("org.gradle.configuration-cache"))) {
            findings.add("Configuration cache enabled (excellent)");
            score += 30;
        } else if (props.containsKey("org.gradle.configuration-cache")) {
            findings.add("Configuration cache partially enabled. Set org.gradle.configuration-cache=true.");
            score += 10;
        } else {
            findings.add("Configuration cache not enabled. "
                    + "Set org.gradle.configuration-cache=true for up to 90% faster configuration.");
        }

        // Parallel
        if ("true".equalsIgnoreCase(props.get("org.gradle.parallel"))) {
            findings.add("Parallel execution enabled (good)");
            score += 15;
        } else {
            findings.add("Parallel execution not enabled. Add org.gradle.parallel=true.");
        }

        // Configure on demand
        if ("true".equalsIgnoreCase(props.get("org.gradle.configureondemand"))) {
            findings.add("Configure-on-demand enabled (good for multi-module)");
            score += 10;
        }

        // JVM args for daemon
        if (props.containsKey("org.gradle.jvmargs")) {
            String jvmArgs = props.get("org.gradle.jvmargs");
            if (jvmArgs != null && jvmArgs.contains("-Xmx")) {
                findings.add("Gradle daemon JVM args configured: " + jvmArgs);
                score += 5;
            }
        }

        // File-system watching
        if ("true".equalsIgnoreCase(props.get("org.gradle.vfs.watch"))) {
            findings.add("File-system watching enabled (good)");
            score += 5;
        }

        // Check for remote build cache
        if (props.containsKey("org.gradle.cache.remote.push")
                || props.containsKey("org.gradle.enterprise.cache.remote")) {
            findings.add("Remote build cache configured (excellent for team builds)");
            score += 10;
        }

        // Check for Gradle Enterprise/Develocity
        try {
            Path settingsFile = dir.resolve("settings.gradle");
            if (!Files.exists(settingsFile)) {
                settingsFile = dir.resolve("settings.gradle.kts");
            }
            if (Files.exists(settingsFile)) {
                String settings = Files.readString(settingsFile);
                if (settings.contains("gradleEnterprise") || settings.contains("develocity")) {
                    findings.add("Gradle Enterprise/Develocity integration detected (advanced)");
                    score += 5;
                }
            }
        } catch (IOException ignored) {
        }

        // Status
        if (score >= 70) {
            health.put("status", "GOOD");
        } else if (score >= 40) {
            health.put("status", "ADEQUATE");
        } else {
            health.put("status", "NEEDS_ATTENTION");
        }

        health.put("score", score);
        health.put("findings", findings);

        // Cache hit rate estimation (from --info output if available)
        health.put(
                "note",
                "For accurate cache hit rates, run: ./gradlew build --info | grep -E '(FROM-CACHE|UP-TO-DATE)'");

        return health;
    }

    private Map<String, Object> analyzeSbtCache(Path dir) {
        Map<String, Object> health = new LinkedHashMap<>();
        List<String> findings = new ArrayList<>();
        int score = 0;

        // Check for execution log
        Path execLogDir = dir.resolve("target/global-logging");
        boolean hasExecLog = Files.isDirectory(execLogDir);

        // Check build.sbt for caching settings
        Path buildSbt = dir.resolve("build.sbt");
        if (!Files.exists(buildSbt)) {
            health.put("status", "NO_PROJECT");
            findings.add("No build.sbt found. Not an SBT project.");
            health.put("findings", findings);
            health.put("score", 0);
            return health;
        }

        try {
            String content = Files.readString(buildSbt);

            // Coursier parallel download
            if (content.contains("coursier") || content.contains("csr")) {
                findings.add("Coursier dependency resolution detected (good)");
                score += 15;
            } else {
                findings.add("Coursier not explicitly configured. "
                        + "Add 'addSbtPlugin(\"io.get-coursier\" % \"sbt-coursier\" % \"2.1.0\")' "
                        + "for parallel dependency downloads.");
            }

            // Incremental compilation (default in SBT 2.x)
            if (content.contains("incOptions") || content.contains("IncrementalCompile")) {
                findings.add("Incremental compilation settings found (good)");
                score += 25;
            } else {
                findings.add("SBT incremental compilation uses defaults. "
                        + "Enable explicitly with: incOptions := incOptions.value.withRecompileOnMacroDef(false)");
            }

            // Parallel execution
            if (content.contains("Global / concurrentRestrictions") || content.contains("parallelExecution")) {
                findings.add("Parallel test execution configured (good)");
                score += 15;
            } else {
                findings.add("Parallel execution not configured. "
                        + "Use: Test / parallelExecution := true, Global / concurrentRestrictions := ...");
            }

            // Cross-build caching (Scala)
            if (content.contains("crossScalaVersions") || content.contains("crossPaths")) {
                findings.add("Cross-build caching available for multi-Scala-version projects");
                score += 10;
            }

            // Execution log analysis
            if (hasExecLog) {
                try (Stream<Path> logs = Files.list(execLogDir)) {
                    List<Path> logFiles = logs.filter(
                                    f -> f.getFileName().toString().startsWith("exec-"))
                            .toList();

                    if (!logFiles.isEmpty()) {
                        // Parse most recent log for cache hits
                        Path latest = logFiles.get(logFiles.size() - 1);
                        String logContent = Files.readString(latest);
                        long cacheHits = countOccurrences(logContent, "cacheHit");
                        long cacheMisses = countOccurrences(logContent, "cacheMiss");

                        if (cacheHits + cacheMisses > 0) {
                            double hitRate = (double) cacheHits / (cacheHits + cacheMisses) * 100;
                            findings.add(String.format(
                                    "Cache stats from execution log: %.0f%% hit rate (%d hits, %d misses)",
                                    hitRate, cacheHits, cacheMisses));
                            score += Math.min(25, (int) (hitRate / 4)); // Up to 25 points
                        }
                    }
                }
            } else if (content.contains("2.")) {
                findings.add("SBT 2.x detected. Execution logs for cache analysis "
                        + "available at target/global-logging/exec-*.log. "
                        + "Run a build first to populate.");
            }

        } catch (IOException ignored) {
        }

        // Status
        if (score >= 60) {
            health.put("status", "GOOD");
        } else if (score >= 30) {
            health.put("status", "ADEQUATE");
        } else {
            health.put("status", "NEEDS_ATTENTION");
        }

        health.put("score", score);
        health.put("findings", findings);
        return health;
    }

    // ─── Optimization generation ────────────────────────────────────────

    private List<Map<String, Object>> generateOptimizations(Path dir, String toolName) {
        return switch (toolName) {
            case "maven" -> generateMavenOptimizations(dir);
            case "gradle" -> generateGradleOptimizations(dir);
            case "sbt" -> generateSbtOptimizations(dir);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> generateMavenOptimizations(Path dir) {
        List<Map<String, Object>> optimizations = new ArrayList<>();

        // 1. Maven Daemon
        optimizations.add(Map.of(
                "area", "Build Daemon",
                "priority", "HIGH",
                "recommendation", "Use mvnd (Maven Daemon) for faster builds",
                "command", "brew install mvndaemon/homebrew-mvnd/mvnd  # or sdk install mvnd",
                "usage", "mvnd clean install  # instead of mvn clean install",
                "estimatedImprovement", "50-80% faster for incremental builds"));

        // 2. Parallel builds
        optimizations.add(Map.of(
                "area", "Parallel Execution",
                "priority", "HIGH",
                "recommendation", "Enable parallel builds with thread count",
                "configFile", ".mvn/maven.config",
                "config", "-T1C  # Uses 1 thread per CPU core\n--no-transfer-progress  # Reduces log noise",
                "estimatedImprovement", "30-60% faster on multi-core machines"));

        // 3. Build cache extension
        optimizations.add(Map.of(
                "area", "Build Cache",
                "priority", "HIGH",
                "recommendation", "Add maven-build-cache-extension for incremental build caching",
                "configFile", "pom.xml (build/extensions)",
                "config",
                        """
                        <build>
                          <extensions>
                            <extension>
                              <groupId>org.apache.maven.extensions</groupId>
                              <artifactId>maven-build-cache-extension</artifactId>
                              <version>1.2.0</version>
                            </extension>
                          </extensions>
                        </build>""",
                "estimatedImprovement", "Up to 90% for repeated builds"));

        // 4. Incremental compilation
        optimizations.add(Map.of(
                "area", "Compiler",
                "priority", "MEDIUM",
                "recommendation", "Enable incremental compilation in maven-compiler-plugin",
                "configFile", "pom.xml (build/plugins)",
                "config",
                        """
                        <plugin>
                          <groupId>org.apache.maven.plugins</groupId>
                          <artifactId>maven-compiler-plugin</artifactId>
                          <configuration>
                            <useIncrementalCompilation>true</useIncrementalCompilation>
                          </configuration>
                        </plugin>""",
                "estimatedImprovement", "20-40% faster compilation"));

        // 5. Skip unnecessary plugins
        optimizations.add(Map.of(
                "area", "Plugin Management",
                "priority", "LOW",
                "recommendation", "Skip plugins that aren't needed for every build",
                "config", "mvn install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true",
                "estimatedImprovement", "10-20% faster for development builds"));

        return optimizations;
    }

    private List<Map<String, Object>> generateGradleOptimizations(Path dir) {
        List<Map<String, Object>> optimizations = new ArrayList<>();

        optimizations.add(Map.of(
                "area", "Build Cache",
                "priority", "HIGH",
                "recommendation", "Enable local build cache for task output reuse",
                "configFile", "gradle.properties",
                "config", "org.gradle.caching=true",
                "estimatedImprovement", "30-50% for repeated tasks"));

        optimizations.add(Map.of(
                "area", "Configuration Cache",
                "priority", "HIGH",
                "recommendation", "Enable configuration cache to skip configuration phase",
                "configFile", "gradle.properties",
                "config", "org.gradle.configuration-cache=true",
                "estimatedImprovement", "Up to 90% faster configuration phase"));

        optimizations.add(Map.of(
                "area", "Parallel Execution",
                "priority", "HIGH",
                "recommendation", "Enable parallel project execution for multi-module builds",
                "configFile", "gradle.properties",
                "config", "org.gradle.parallel=true",
                "estimatedImprovement", "30-50% for multi-module projects"));

        optimizations.add(Map.of(
                "area", "Configure on Demand",
                "priority", "MEDIUM",
                "recommendation", "Only configure projects that are needed for the requested tasks",
                "configFile", "gradle.properties",
                "config", "org.gradle.configureondemand=true",
                "estimatedImprovement", "20-30% for large multi-module projects"));

        optimizations.add(Map.of(
                "area", "JVM Daemon",
                "priority", "MEDIUM",
                "recommendation", "Optimize Gradle daemon JVM memory and GC settings",
                "configFile", "gradle.properties",
                "config",
                        """
                        org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m \\
                          -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8""",
                "estimatedImprovement", "10-20% for memory-intensive builds"));

        optimizations.add(Map.of(
                "area", "Remote Cache",
                "priority", "MEDIUM",
                "recommendation", "Configure remote build cache for team-wide caching",
                "configFile", "settings.gradle",
                "config",
                        """
                        buildCache {
                          remote(HttpBuildCache) {
                            url = 'https://your-build-cache.example.com/cache/'
                            credentials { username = 'ci' }
                          }
                        }""",
                "estimatedImprovement", "50-80% shared across team"));

        return optimizations;
    }

    private List<Map<String, Object>> generateSbtOptimizations(Path dir) {
        List<Map<String, Object>> optimizations = new ArrayList<>();

        optimizations.add(Map.of(
                "area", "Dependency Resolution",
                "priority", "HIGH",
                "recommendation", "Use Coursier for parallel dependency downloads",
                "configFile", "project/plugins.sbt",
                "config", "addSbtPlugin(\"io.get-coursier\" % \"sbt-coursier\" % \"2.1.0\")",
                "estimatedImprovement", "30-50% faster dependency resolution"));

        optimizations.add(Map.of(
                "area", "Incremental Compilation",
                "priority", "HIGH",
                "recommendation", "Optimize incremental compilation settings",
                "configFile", "build.sbt",
                "config",
                        """
                        incOptions := incOptions.value
                          .withRecompileOnMacroDef(false)
                          .withApiDebug(false)""",
                "estimatedImprovement", "Up to 80% for repeated compilations"));

        optimizations.add(Map.of(
                "area", "Parallel Testing",
                "priority", "MEDIUM",
                "recommendation", "Enable parallel test execution",
                "configFile", "build.sbt",
                "config",
                        """
                        Test / parallelExecution := true
                        Global / concurrentRestrictions += Tags.limit(Tags.Test, 4)""",
                "estimatedImprovement", "40-60% faster test execution"));

        optimizations.add(Map.of(
                "area", "JVM Forking",
                "priority", "MEDIUM",
                "recommendation", "Configure JVM forking to reuse JVM across runs",
                "configFile", "build.sbt",
                "config",
                        """
                        fork := true
                        javaOptions ++= Seq("-Xmx2g", "-XX:+UseG1GC")""",
                "estimatedImprovement", "15-25% for JVM-heavy tasks"));

        optimizations.add(Map.of(
                "area", "Turbo Mode",
                "priority", "LOW",
                "recommendation", "Enable turbo mode for faster classloading (SBT 2.x)",
                "configFile", "build.sbt",
                "config", "Global / turbo := true",
                "estimatedImprovement", "10-20% classloading speedup"));

        return optimizations;
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static long countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
