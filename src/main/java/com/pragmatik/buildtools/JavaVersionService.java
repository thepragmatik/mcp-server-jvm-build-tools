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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP service for Java version compatibility analysis.
 * <p>
 * Checks if a project and its dependencies support a target Java version.
 * Important when upgrading Java versions (e.g., 17→21 or 21→25).
 * <p>
 * Registered MCP tools:
 * <ul>
 *   <li>{@code check_java_compatibility} — scan project for Java version compatibility issues</li>
 * </ul>
 */
@Service
public class JavaVersionService {

    // Java version lifecycle: GA date, EOL date
    private static final Map<Integer, Map<String, String>> JAVA_LIFECYCLE = new LinkedHashMap<>();
    static {
        JAVA_LIFECYCLE.put(8,  Map.of("ga","2014-03","eol","2030-12","lts","true"));
        JAVA_LIFECYCLE.put(11, Map.of("ga","2018-09","eol","2032-01","lts","true"));
        JAVA_LIFECYCLE.put(17, Map.of("ga","2021-09","eol","2029-12","lts","true"));
        JAVA_LIFECYCLE.put(21, Map.of("ga","2023-09","eol","2031-12","lts","true"));
        JAVA_LIFECYCLE.put(22, Map.of("ga","2024-03","eol","2024-09","lts","false"));
        JAVA_LIFECYCLE.put(23, Map.of("ga","2024-09","eol","2025-03","lts","false"));
        JAVA_LIFECYCLE.put(24, Map.of("ga","2025-03","eol","2025-09","lts","false"));
        JAVA_LIFECYCLE.put(25, Map.of("ga","2025-09","eol","2033-12","lts","true"));
    }

    // Known incompatible changes between Java versions
    private static final Map<Integer, List<String>> BREAKING_CHANGES = new LinkedHashMap<>();
    static {
        BREAKING_CHANGES.put(17, List.of(
                "Strong encapsulation of JDK internals (--add-opens may be required)",
                "Removed RMI Activation",
                "Removed Applet API",
                "Removed experimental AOT and JIT compilers",
                "Security manager deprecated for removal"
        ));
        BREAKING_CHANGES.put(21, List.of(
                "Virtual threads (preview→final) — library compatibility may vary",
                "Sequenced collections API changes",
                "Scoped values (preview→final)",
                "Key encapsulation mechanism API",
                "Deprecated 32-bit x86 port for removal"
        ));
        BREAKING_CHANGES.put(25, List.of(
                "String templates removed (was preview, now withdrawn)",
                "Vector API (incubating since 16) may have API changes",
                "Foreign function & memory API finalized — JNI usage patterns may need updating",
                "Structured concurrency finalized",
                "Removal of Security Manager"
        ));
    }

    // Known dependency version requirements for common frameworks
    private static final Map<String, Map<String, String>> FRAMEWORK_JAVA_MIN = new LinkedHashMap<>();
    static {
        FRAMEWORK_JAVA_MIN.put("org.springframework.boot:spring-boot", Map.of(
                "2.7", "8", "3.0", "17", "3.1", "17", "3.2", "17",
                "3.3", "17", "3.4", "17", "3.5", "17"));
        FRAMEWORK_JAVA_MIN.put("org.springframework:spring-core", Map.of(
                "5.3", "8", "6.0", "17", "6.1", "17", "6.2", "17"));
        FRAMEWORK_JAVA_MIN.put("jakarta.servlet:jakarta.servlet-api", Map.of(
                "5.0", "11", "6.0", "11", "6.1", "17"));
        FRAMEWORK_JAVA_MIN.put("org.hibernate:hibernate-core", Map.of(
                "5.6", "8", "6.0", "11", "6.1", "11", "6.2", "11",
                "6.3", "17", "6.4", "17", "6.5", "17"));
        FRAMEWORK_JAVA_MIN.put("org.apache.tomcat:tomcat", Map.of(
                "9.0", "8", "10.0", "11", "10.1", "11", "11.0", "17"));
        FRAMEWORK_JAVA_MIN.put("org.junit.jupiter:junit-jupiter", Map.of(
                "5.7", "8", "5.8", "8", "5.9", "8", "5.10", "8", "5.11", "8"));
        FRAMEWORK_JAVA_MIN.put("org.mockito:mockito-core", Map.of(
                "4.0", "8", "5.0", "11", "5.4", "11"));
        FRAMEWORK_JAVA_MIN.put("ch.qos.logback:logback-classic", Map.of(
                "1.2", "8", "1.3", "11", "1.4", "11", "1.5", "11"));
    }

    private static final Pattern MAVEN_JAVA_VERSION = Pattern.compile(
            "<(?:maven\\.compiler\\.(?:source|target|release)|java\\.version)>\\s*([0-9]+)\\s*<");
    private static final Pattern MAVEN_DEP = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>" +
            "\\s*(?:<version>([^<]*)</version>)?", Pattern.DOTALL);

    private static final Pattern GRADLE_JAVA_VERSION = Pattern.compile(
            "(?:sourceCompatibility|targetCompatibility|JavaVersion)\\s*[=:]\\s*['\"]?(?:JavaVersion\\.)?VERSION_?(\\d+(?:_\\d+)?)['\"]?\\s*");
    private static final Pattern GRADLE_TOOLCHAIN = Pattern.compile(
            "languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\((\\d+)\\)");

    @Tool(name = "check_java_compatibility",
          description = "Check if a JVM project and its dependencies are compatible with " +
                        "a target Java version. Scans Maven compiler settings, Gradle source/target " +
                        "compatibility, and known framework minimum Java version requirements. " +
                        "Returns compatibility report with warnings for known breaking changes " +
                        "between versions. Essential before Java version upgrades (e.g., 17→21→25).")
    public String checkJavaCompatibility(
            @ToolParam(required = true, description = "Path to the project directory")
            String projectDir,
            @Schema(allowableValues = {"8","11","17","21","22","23","24","25"})
            @ToolParam(required = false, description = "Target Java version to check compatibility against. " +
                "If omitted, checks against the project's currently configured Java version.")
            String targetJavaVersion) {

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
        result.put("status", "success");

        // Determine current and target Java versions
        int currentJavaVersion = detectCurrentJavaVersion(dir);
        int target = (targetJavaVersion != null && !targetJavaVersion.isBlank())
                ? Integer.parseInt(targetJavaVersion.trim()) : currentJavaVersion;

        result.put("currentJavaVersion", currentJavaVersion);
        result.put("targetJavaVersion", target);

        // Build tool detection
        BuildTool tool = new BuildToolProvider(
                List.of(new MavenBuildTool(), new GradleBuildTool(), new SbtBuildTool()))
                .resolve(null, dir);
        result.put("buildTool", tool.getName());

        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // 1. Check project's configured Java version vs target
        if (target > currentJavaVersion) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("type", "project_configuration");
            issue.put("severity", "WARNING");
            issue.put("message", "Project currently targets Java " + currentJavaVersion +
                    ". Upgrade compiler settings to Java " + target + ".");
            issue.put("fix", buildToolJavaUpgradeInstructions(tool.getName(), target));
            warnings.add(issue);
        }

        // 2. Extract dependencies and check known framework requirements
        List<Map<String, String>> dependencies = extractDependencies(dir, tool.getName());
        result.put("dependencyCount", dependencies.size());

        List<Map<String, Object>> depIssues = new ArrayList<>();
        for (Map<String, String> dep : dependencies) {
            String gid = dep.get("groupId");
            String aid = dep.get("artifactId");
            String version = dep.get("version");
            if (version == null || version.isBlank() || "[managed]".equals(version)) continue;

            String frameworkKey = gid + ":" + aid;
            Map<String, String> versionReqs = FRAMEWORK_JAVA_MIN.get(frameworkKey);
            if (versionReqs != null) {
                String minJavaStr = findMinJavaVersion(versionReqs, version);
                if (minJavaStr != null) {
                    int minJava = Integer.parseInt(minJavaStr);
                    if (minJava > currentJavaVersion) {
                        Map<String, Object> di = new LinkedHashMap<>();
                        di.put("dependency", frameworkKey);
                        di.put("currentVersion", version);
                        di.put("requiresJavaVersion", minJava);
                        di.put("severity", "WARNING");
                        di.put("message", frameworkKey + " " + version +
                                " requires Java " + minJava + "+ but project targets Java " +
                                currentJavaVersion + ".");
                        depIssues.add(di);
                    }
                    if (minJava > target && target < currentJavaVersion) {
                        Map<String, Object> di = new LinkedHashMap<>();
                        di.put("dependency", frameworkKey);
                        di.put("currentVersion", version);
                        di.put("requiresJavaVersion", minJava);
                        di.put("severity", "INFO");
                        di.put("message", frameworkKey + " " + version +
                                " requires Java " + minJava + "+. Downgrade may be needed if targeting Java " + target + ".");
                        depIssues.add(di);
                    }
                }
            }
        }

        if (!depIssues.isEmpty()) {
            result.put("dependencyCompatibilityIssues", depIssues);
            long critWarnings = depIssues.stream().filter(d -> "WARNING".equals(d.get("severity"))).count();
            if (critWarnings > 0) {
                issues.add(Map.of("type", "dependency_compatibility",
                        "severity", "WARNING",
                        "message", critWarnings + " dependencies may require a higher Java version than currently configured."));
            }
        }

        // 3. Add known breaking changes between current and target versions
        for (Map.Entry<Integer, List<String>> entry : BREAKING_CHANGES.entrySet()) {
            int breakVersion = entry.getKey();
            if (breakVersion > currentJavaVersion && breakVersion <= target) {
                Map<String, Object> breaking = new LinkedHashMap<>();
                breaking.put("type", "breaking_change");
                breaking.put("javaVersion", breakVersion);
                breaking.put("severity", "WARNING");
                breaking.put("changes", entry.getValue());
                warnings.add(breaking);
            }
        }

        // 4. Check if target Java version is LTS
        Map<String, String> lifecycle = JAVA_LIFECYCLE.get(target);
        if (lifecycle != null) {
            Map<String, Object> targetInfo = new LinkedHashMap<>();
            targetInfo.put("version", target);
            targetInfo.put("isLTS", "true".equals(lifecycle.get("lts")));
            targetInfo.put("gaDate", lifecycle.get("ga"));
            targetInfo.put("eolDate", lifecycle.get("eol"));
            result.put("targetVersionInfo", targetInfo);

            if (!"true".equals(lifecycle.get("lts"))) {
                warnings.add(Map.of(
                        "type", "non_lts",
                        "severity", "INFO",
                        "message", "Java " + target + " is not an LTS release. " +
                                "LTS versions receive longer support. " +
                                "Consider upgrading to the next LTS instead."
                ));
            }
        }

        // 5. Generate recommendations
        if (target > currentJavaVersion) {
            recommendations.add("Update compiler settings: " +
                    buildToolJavaUpgradeInstructions(tool.getName(), target));
            recommendations.add("Review breaking changes for versions " +
                    (currentJavaVersion + 1) + " through " + target);
            recommendations.add("Run full test suite after Java upgrade");
            recommendations.add("Check for deprecated APIs removed in newer Java versions");
        }

        // Maven-specific: suggest release flag
        if ("maven".equals(tool.getName())) {
            recommendations.add("Use <release>" + target + "</release> instead of <source>/<target> " +
                    "for stricter Java version enforcement (Maven compiler plugin 3.6+).");
        }

        result.put("issueCount", issues.size() + warnings.size());
        result.put("issues", issues);
        result.put("warnings", warnings);
        result.put("recommendations", recommendations);

        // Overall compatibility verdict
        long errorCount = issues.stream().filter(i -> "ERROR".equals(i.get("severity"))).count();
        long warningCount = issues.size() + warnings.size() - errorCount;

        Map<String, Object> verdict = new LinkedHashMap<>();
        if (errorCount > 0) {
            verdict.put("compatible", false);
            verdict.put("message", "NOT compatible — " + errorCount + " blocking issues found.");
        } else if (warningCount > 0) {
            verdict.put("compatible", true);
            verdict.put("message", "Compatible with caveats — " + warningCount +
                    " warnings to review.");
        } else {
            verdict.put("compatible", true);
            verdict.put("message", "Fully compatible — no issues detected.");
        }
        result.put("verdict", verdict);

        return JsonUtils.toJson(result);
    }

    private int detectCurrentJavaVersion(Path dir) {
        // Check Maven
        Path pomXml = dir.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            try {
                String content = Files.readString(pomXml);
                // Check for maven.compiler.release first (Java 9+ preferred)
                Pattern releasePattern = Pattern.compile(
                        "<maven\\.compiler\\.release>\\s*([0-9]+)\\s*<");
                Matcher rm = releasePattern.matcher(content);
                if (rm.find()) return Integer.parseInt(rm.group(1));

                // Fall back to source/target
                Matcher m = MAVEN_JAVA_VERSION.matcher(content);
                int maxVersion = 0;
                while (m.find()) {
                    int v = Integer.parseInt(m.group(1));
                    if (v > maxVersion) maxVersion = v;
                }
                if (maxVersion > 0) return maxVersion;

                // Java version property
                Pattern jv = Pattern.compile("<java\\.version>\\s*([0-9]+)\\s*<");
                Matcher jvm = jv.matcher(content);
                if (jvm.find()) return Integer.parseInt(jvm.group(1));

                return 8; // Default for Maven
            } catch (IOException ignored) {}
        }

        // Check Gradle
        for (String gf : new String[]{"build.gradle.kts", "build.gradle"}) {
            Path gradleFile = dir.resolve(gf);
            if (Files.exists(gradleFile)) {
                try {
                    String content = Files.readString(gradleFile);

                    // Check toolchain first (most reliable)
                    Matcher tc = GRADLE_TOOLCHAIN.matcher(content);
                    if (tc.find()) return Integer.parseInt(tc.group(1));

                    // Check sourceCompatibility
                    Matcher m = GRADLE_JAVA_VERSION.matcher(content);
                    int maxVersion = 0;
                    while (m.find()) {
                        String v = m.group(1).replace("_", ".");
                        // Handle JavaVersion.VERSION_1_8 → 8
                        if (v.startsWith("1.")) v = v.substring(2);
                        try {
                            int iv = Integer.parseInt(v.split("\\.")[0]);
                            if (iv > maxVersion) maxVersion = iv;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (maxVersion > 0) return maxVersion;
                } catch (IOException ignored) {}
            }
        }

        // Check SBT
        Path buildSbt = dir.resolve("build.sbt");
        if (Files.exists(buildSbt)) {
            try {
                String content = Files.readString(buildSbt);
                Pattern sv = Pattern.compile("javacOptions\\s*\\+\\+=\\s*Seq\\(\"-source\",\\s*\"([0-9]+)\"\\)");
                Matcher sm = sv.matcher(content);
                if (sm.find()) return Integer.parseInt(sm.group(1));
            } catch (IOException ignored) {}
        }

        return 17; // Default assumption for modern projects
    }

    private String buildToolJavaUpgradeInstructions(String tool, int targetVersion) {
        return switch (tool) {
            case "maven" -> "Add <maven.compiler.release>" + targetVersion +
                    "</maven.compiler.release> to <properties> in pom.xml, " +
                    "or update <source>/<target> in maven-compiler-plugin configuration.";
            case "gradle" -> "Set java { toolchain { languageVersion = JavaLanguageVersion.of(" +
                    targetVersion + ") } } or update sourceCompatibility/targetCompatibility.";
            case "sbt" -> "Set javacOptions ++= Seq(\"-source\", \"" + targetVersion +
                    "\", \"-target\", \"" + targetVersion + "\")";
            default -> "Update compiler settings to target Java " + targetVersion;
        };
    }

    private List<Map<String, String>> extractDependencies(Path dir, String tool) {
        List<Map<String, String>> deps = new ArrayList<>();

        if ("maven".equals(tool)) {
            Path pomXml = dir.resolve("pom.xml");
            if (Files.exists(pomXml)) {
                try {
                    String content = Files.readString(pomXml);
                    Matcher m = MAVEN_DEP.matcher(content);
                    while (m.find()) {
                        Map<String, String> dep = new LinkedHashMap<>();
                        dep.put("groupId", m.group(1).trim());
                        dep.put("artifactId", m.group(2).trim());
                        String v = m.group(3);
                        dep.put("version", v != null && !v.isBlank() ? v.trim() : "");
                        deps.add(dep);
                    }
                } catch (IOException ignored) {}
            }
        }

        // For Gradle and SBT, just return what we can parse
        if ("gradle".equals(tool)) {
            for (String gf : new String[]{"build.gradle.kts", "build.gradle"}) {
                Path gfPath = dir.resolve(gf);
                if (Files.exists(gfPath)) {
                    try {
                        String content = Files.readString(gfPath);
                        Pattern gp = Pattern.compile(
                                "(\\w+)\\s+'(\\w+):([^:']+):([^']+)'");
                        Matcher m = gp.matcher(content);
                        while (m.find()) {
                            Map<String, String> dep = new LinkedHashMap<>();
                            dep.put("groupId", m.group(2).trim());
                            dep.put("artifactId", m.group(3).trim());
                            dep.put("version", m.group(4).trim());
                            deps.add(dep);
                        }
                    } catch (IOException ignored) {}
                }
            }
        }

        return deps;
    }

    private String findMinJavaVersion(Map<String, String> versionReqs, String currentVersion) {
        // Find the closest matching major.minor version
        String bestMatch = null;
        String bestKey = null;
        for (String key : versionReqs.keySet()) {
            if (currentVersion.startsWith(key)) return versionReqs.get(key);
            // Store the closest lower version
            if (compareVersions(key, currentVersion) <= 0) {
                if (bestKey == null || compareVersions(key, bestKey) > 0) {
                    bestKey = key;
                    bestMatch = versionReqs.get(key);
                }
            }
        }
        return bestMatch;
    }

    private int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int max = Math.max(p1.length, p2.length);
        for (int i = 0; i < max; i++) {
            int n1 = i < p1.length ? Integer.parseInt(p1[i]) : 0;
            int n2 = i < p2.length ? Integer.parseInt(p2[i]) : 0;
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }
}
