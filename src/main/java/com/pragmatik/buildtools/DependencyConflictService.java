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
 * MCP service for detecting dependency version conflicts across Maven, Gradle, and SBT projects.
 */
@Service
public class DependencyConflictService {

    private static final Pattern MAVEN_DEP_PATTERN = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>" +
            "\\s*(?:<version>([^<]*)</version>)?(?:\\s*<scope>([^<]*)</scope>)?",
            Pattern.DOTALL);

    private static final Pattern MAVEN_DEP_MGMT_PATTERN = Pattern.compile(
            "<dependencyManagement>.*?</dependencyManagement>", Pattern.DOTALL);

    private static final Pattern MAVEN_PROPERTY_PATTERN = Pattern.compile(
            "<([a-zA-Z0-9._-]+)>([^<]+)</\\1>");

    private static final Pattern GRADLE_DEP_PATTERN = Pattern.compile(
            "(\\w+)\\s+'([\\w.]+):(\\w+):([^']+)'");

    private static final Pattern GRADLE_DEP_KTS_PATTERN = Pattern.compile(
            "(\\w+)\\s*\\(\\s*\"([\\w.]+):([^\"]+):([^\"]+)\"\\s*\\)");

    private static final Pattern SBT_DEP_PATTERN = Pattern.compile(
            "\"([^\"]+)\"\\s*(%{1,2})\\s*\"([^\"]+)\"\\s*%\\s*\"([^\"]+)\"");

    @Tool(name = "detect_dependency_conflicts",
          description = "Scan a JVM project for dependency version conflicts. " +
                        "Detects duplicate dependencies with different versions, conflicts between " +
                        "direct deps and dependency management/BOM versions, and transitive override risks. " +
                        "Works with Maven (pom.xml), Gradle (build.gradle/.kts), and SBT (build.sbt). " +
                        "Returns structured JSON with conflict details, severity, and resolution suggestions.")
    public String detectDependencyConflicts(
            @ToolParam(required = true, description = "Path to the project directory")
            String projectDir,
            @Schema(allowableValues = {"maven", "gradle", "sbt", "all"})
            @ToolParam(required = false, description = "Build tool scope: 'maven', 'gradle', 'sbt', or 'all' (default)")
            String scope) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return JsonUtils.errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return JsonUtils.errorJson("Project directory is not valid: " + projectDir);
        }

        String effectiveScope = (scope == null || scope.isBlank()) ? "all" : scope.toLowerCase().trim();
        String projectName = dir.getFileName().toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", projectName);
        result.put("projectDir", dir.toString());
        result.put("scope", effectiveScope);
        result.put("status", "success");

        List<Map<String, Object>> allConflicts = new ArrayList<>();
        List<String> filesAnalyzed = new ArrayList<>();

        // Analyze build files based on scope
        Path pomXml = dir.resolve("pom.xml");
        if (Files.exists(pomXml) && (effectiveScope.equals("maven") || effectiveScope.equals("all"))) {
            try {
                allConflicts.addAll(analyzeMavenConflicts(pomXml));
                filesAnalyzed.add("pom.xml");
            } catch (IOException ignored) {}
        }

        if (effectiveScope.equals("gradle") || effectiveScope.equals("all")) {
            for (String gf : new String[]{"build.gradle.kts", "build.gradle"}) {
                Path gradleFile = dir.resolve(gf);
                if (Files.exists(gradleFile)) {
                    try {
                        allConflicts.addAll(analyzeGradleConflicts(gradleFile));
                        filesAnalyzed.add(gf);
                    } catch (IOException ignored) {}
                    break;
                }
            }
        }

        Path buildSbt = dir.resolve("build.sbt");
        if (Files.exists(buildSbt) && (effectiveScope.equals("sbt") || effectiveScope.equals("all"))) {
            try {
                allConflicts.addAll(analyzeSbtConflicts(buildSbt));
                filesAnalyzed.add("build.sbt");
            } catch (IOException ignored) {}
        }

        result.put("filesAnalyzed", filesAnalyzed);
        result.put("conflictCount", allConflicts.size());
        result.put("conflicts", allConflicts);

        // Summary
        long errors = allConflicts.stream().filter(c -> "ERROR".equals(c.get("severity"))).count();
        long warnings = allConflicts.stream().filter(c -> "WARNING".equals(c.get("severity"))).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("errorCount", errors);
        summary.put("warningCount", warnings);
        if (allConflicts.isEmpty()) {
            summary.put("message", "No dependency version conflicts detected.");
        } else if (errors > 0) {
            summary.put("message", errors + " error-level conflicts found — resolve before building.");
        } else {
            summary.put("message", warnings + " warning-level issues found — review at your convenience.");
        }
        result.put("summary", summary);

        // Resolution plan
        List<String> steps = new ArrayList<>();
        if (!allConflicts.isEmpty()) {
            steps.add("Review each conflict and choose the target version");
            steps.add("Use dependencyManagement (Maven), version catalogs (Gradle), or vals (SBT) to centralize versions");
            steps.add("Re-run detect_dependency_conflicts after resolving to verify");
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("action", allConflicts.isEmpty() ? "NONE" : "RESOLVE");
        plan.put("message", allConflicts.isEmpty() ? "No action needed." : "Resolve " + allConflicts.size() + " conflict(s).");
        plan.put("steps", steps);
        result.put("resolutionPlan", plan);

        return JsonUtils.toJson(result);
    }

    List<Map<String, Object>> analyzeMavenConflicts(Path pomXml) throws IOException {
        String content = Files.readString(pomXml);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, String> properties = parseMavenProperties(content);
        Map<String, Map<String, List<VerInfo>>> allDeps = new LinkedHashMap<>();
        parseMavenDeps(content, MAVEN_DEP_PATTERN, allDeps, "dependency", properties);

        Matcher dm = MAVEN_DEP_MGMT_PATTERN.matcher(content);
        if (dm.find()) {
            parseMavenDeps(dm.group(), MAVEN_DEP_PATTERN, allDeps, "dependencyManagement", properties);
        }

        conflicts = detectConflicts(allDeps, "maven");
        return conflicts;
    }

    List<Map<String, Object>> analyzeGradleConflicts(Path gradleFile) throws IOException {
        String content = Files.readString(gradleFile);
        boolean isKts = gradleFile.getFileName().toString().endsWith(".kts");
        Pattern pattern = isKts ? GRADLE_DEP_KTS_PATTERN : GRADLE_DEP_PATTERN;
        Map<String, Map<String, List<VerInfo>>> allDeps = new LinkedHashMap<>();
        Map<String, String> versionCatalog = new LinkedHashMap<>();

        Pattern valPattern = Pattern.compile("val\\s+(\\w+[Vv]ersion)\\s*=\\s*\"([^\"]+)\"");
        Matcher vm = valPattern.matcher(content);
        while (vm.find()) versionCatalog.put(vm.group(1).trim(), vm.group(2).trim());

        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String config = m.group(1).trim();
            String groupId = m.group(2).trim();
            String artifactId = m.group(3).trim();
            String version = m.group(4).trim();
            if (version.startsWith("libs.") || version.contains("Version")) {
                String r = versionCatalog.get(version);
                if (r != null) version = r;
            }
            allDeps.computeIfAbsent(groupId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(artifactId, k -> new ArrayList<>())
                    .add(new VerInfo(version, "dependency", config));
        }
        return detectConflicts(allDeps, "gradle");
    }

    List<Map<String, Object>> analyzeSbtConflicts(Path buildSbt) throws IOException {
        String content = Files.readString(buildSbt);
        Map<String, Map<String, List<VerInfo>>> allDeps = new LinkedHashMap<>();
        Matcher m = SBT_DEP_PATTERN.matcher(content);
        while (m.find()) {
            String groupId = m.group(1).trim();
            String artifactId = m.group(3).trim();
            String version = m.group(4).trim();
            boolean sv = "%%".equals(m.group(2));
            allDeps.computeIfAbsent(groupId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(artifactId, k -> new ArrayList<>())
                    .add(new VerInfo(version, "dependency", sv ? "scala-versioned" : "java"));
        }
        return detectConflicts(allDeps, "sbt");
    }

    private List<Map<String, Object>> detectConflicts(
            Map<String, Map<String, List<VerInfo>>> allDeps, String buildTool) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<VerInfo>>> ge : allDeps.entrySet()) {
            String gid = ge.getKey();
            for (Map.Entry<String, List<VerInfo>> ae : ge.getValue().entrySet()) {
                String aid = ae.getKey();
                List<VerInfo> vers = ae.getValue();
                if (vers.size() <= 1) continue;
                Set<String> unique = new LinkedHashSet<>();
                for (VerInfo vi : vers) unique.add(vi.version);
                if (unique.size() <= 1) continue;

                boolean hasDirect = vers.stream().anyMatch(v -> "dependency".equals(v.source));
                boolean hasManaged = vers.stream().anyMatch(v -> "dependencyManagement".equals(v.source));
                String severity = (hasDirect && hasManaged) ? "ERROR" : "WARNING";

                Map<String, Object> c = new LinkedHashMap<>();
                c.put("groupId", gid);
                c.put("artifactId", aid);
                c.put("severity", severity);
                List<Map<String, Object>> vds = new ArrayList<>();
                for (VerInfo vi : vers) {
                    Map<String, Object> vd = new LinkedHashMap<>();
                    vd.put("version", vi.version);
                    vd.put("source", vi.source);
                    vd.put("scope", vi.scope);
                    vds.add(vd);
                }
                c.put("versions", vds);
                c.put("affectedBuildTool", buildTool);
                c.put("suggestion", hasDirect && hasManaged
                    ? "Remove version from direct declaration (inherits from dependencyManagement), or align both."
                    : "Consolidate duplicate declarations to a single version.");
                conflicts.add(c);
            }
        }
        return conflicts;
    }

    private Map<String, String> parseMavenProperties(String content) {
        Map<String, String> props = new LinkedHashMap<>();
        Pattern p = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL);
        Matcher m = p.matcher(content);
        if (m.find()) {
            Matcher pm = MAVEN_PROPERTY_PATTERN.matcher(m.group(1));
            while (pm.find()) {
                String k = pm.group(1);
                if (!k.startsWith("!")) props.put(k, pm.group(2).trim());
            }
        }
        return props;
    }

    private void parseMavenDeps(String content, Pattern pattern,
                                 Map<String, Map<String, List<VerInfo>>> allDeps,
                                 String source, Map<String, String> properties) {
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String gid = m.group(1).trim();
            String aid = m.group(2).trim();
            String ver = m.group(3);
            String scope = m.group(4);
            if (ver != null && ver.startsWith("${") && ver.endsWith("}")) {
                String pn = ver.substring(2, ver.length() - 1);
                String r = properties.get(pn);
                if (r != null) ver = r;
            }
            String ev = (ver != null && !ver.isBlank()) ? ver.trim() : "[managed]";
            String es = (scope != null && !scope.isBlank()) ? scope.trim() : "compile";
            allDeps.computeIfAbsent(gid, k -> new LinkedHashMap<>())
                    .computeIfAbsent(aid, k -> new ArrayList<>())
                    .add(new VerInfo(ev, source, es));
        }
    }

    private static class VerInfo {
        final String version, source, scope;
        VerInfo(String v, String s, String sc) { version = v; source = s; scope = sc; }
    }
}
