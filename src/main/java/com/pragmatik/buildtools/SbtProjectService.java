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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * SBT project analysis service providing multi-module detection,
 * test framework identification, and build structure analysis.
 * <p>
 * Complements {@link SbtBuildTool} by adding introspection tools that
 * analyze build.sbt files without executing SBT, making them safe for
 * use in read-only or CI contexts.
 */
@Service
public class SbtProjectService {

    private static final Set<String> KNOWN_TEST_FRAMEWORKS =
            Set.of("scalatest", "specs2", "munit", "utest", "scalacheck", "junit-interface", "junit", "weaver");

    private static final Set<String> KNOWN_PLUGINS = Set.of(
            "sbt-assembly",
            "sbt-native-packager",
            "sbt-docker",
            "sbt-release",
            "sbt-scalafmt",
            "sbt-scoverage",
            "sbt-buildinfo",
            "sbt-git",
            "sbt-header",
            "sbt-ci-release");

    /**
     * Detect subprojects (modules) in a multi-module SBT build.
     * Parses build.sbt and any *.sbt files in the project/ directory
     * for lazy val or project definitions.
     */
    @Tool(
            name = "detect_sbt_modules",
            description = "Detect subprojects/modules in a multi-module SBT build. "
                    + "Parses build.sbt for lazy val or project definitions. "
                    + "Returns module names, their base directories, and aggregated status.")
    public String detectSbtModules(
            @ToolParam(required = true, description = "Project directory path containing build.sbt")
                    String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return errorJson("Cannot resolve project directory: " + e.getMessage());
        }

        Path buildFile = dir.resolve("build.sbt");
        if (!Files.exists(buildFile)) {
            return errorJson("No build.sbt found in: " + projectDir);
        }

        try {
            String content = Files.readString(buildFile);
            List<Map<String, Object>> modules = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            // Match: lazy val moduleName = project.in(file("path"))
            // Also: lazy val moduleName = (project in file("path"))
            // Also: lazy val moduleName = Project(id = "name", base = file("path"))
            Pattern lazyValPat = Pattern.compile(
                    "lazy\\s+val\\s+(\\w+)\\s*=\\s*(?:project|Project)\\s*(?:\\(.*?\\))?\\s*\\.\\s*in\\s*\\(?\\s*file\\s*\\(\\s*\"([^\"]+)\"\\s*\\)",
                    Pattern.DOTALL);
            Matcher m = lazyValPat.matcher(content);
            while (m.find()) {
                String name = m.group(1).trim();
                String baseDir = m.group(2).trim();
                if (seen.add(name)) {
                    Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put("name", name);
                    mod.put("baseDirectory", baseDir);
                    mod.put("existsOnDisk", Files.isDirectory(dir.resolve(baseDir)));
                    modules.add(mod);
                }
            }

            // Also match: lazy val name = project (inline without .in)
            Pattern simplePat = Pattern.compile("lazy\\s+val\\s+(\\w+)\\s*=\\s*project\\b(?!\\s*\\.)", Pattern.DOTALL);
            Matcher m2 = simplePat.matcher(content);
            while (m2.find()) {
                String name = m2.group(1).trim();
                if (seen.add(name)) {
                    Map<String, Object> mod = new LinkedHashMap<>();
                    mod.put("name", name);
                    mod.put("baseDirectory", null);
                    mod.put("existsOnDisk", false);
                    modules.add(mod);
                }
            }

            // Detect aggregate/aggregateProjects
            boolean hasAggregate = false;
            List<String> aggregated = new ArrayList<>();
            Pattern aggPat = Pattern.compile("\\.aggregate\\s*\\(([^)]+)\\)");
            Matcher am = aggPat.matcher(content);
            if (am.find()) {
                hasAggregate = true;
                String aggList = am.group(1);
                for (String part : aggList.split(",")) {
                    aggregated.add(part.trim());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("project", dir.getFileName().toString());
            result.put("projectDir", dir.toString());
            result.put("buildFile", "build.sbt");
            result.put("multiModule", modules.size() > 1);
            result.put("moduleCount", modules.size());
            result.put("modules", modules);
            result.put("hasRootProject", content.contains("lazy val root"));
            if (hasAggregate) {
                result.put("aggregatedModules", aggregated);
            }

            // Also check project/*.scala for Build.scala-style definitions
            Path projectDir_scala = dir.resolve("project");
            if (Files.isDirectory(projectDir_scala)) {
                try (var stream = Files.list(projectDir_scala)) {
                    List<String> scalaFiles = stream.filter(
                                    f -> f.getFileName().toString().endsWith(".scala"))
                            .map(f -> f.getFileName().toString())
                            .toList();
                    if (!scalaFiles.isEmpty()) {
                        result.put("projectScalaFiles", scalaFiles);
                        result.put(
                                "note",
                                "Build definition also found in project/*.scala files — "
                                        + "these may contain additional module definitions.");
                    }
                }
            }

            return toJson(result);
        } catch (IOException e) {
            return errorJson("Failed to read build.sbt: " + e.getMessage());
        }
    }

    /**
     * Detect which test frameworks are configured in an SBT project.
     */
    @Tool(
            name = "detect_sbt_test_frameworks",
            description = "Detect which test frameworks are configured in an SBT build. "
                    + "Parses libraryDependencies in build.sbt for known test frameworks "
                    + "(ScalaTest, specs2, MUnit, uTest, ScalaCheck, JUnit, Weaver). "
                    + "Returns detected frameworks with version information.")
    public String detectSbtTestFrameworks(
            @ToolParam(required = true, description = "Project directory path containing build.sbt")
                    String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return errorJson("Cannot resolve project directory: " + e.getMessage());
        }

        Path buildFile = dir.resolve("build.sbt");
        if (!Files.exists(buildFile)) {
            return errorJson("No build.sbt found in: " + projectDir);
        }

        try {
            String content = Files.readString(buildFile);
            List<Map<String, Object>> frameworks = new ArrayList<>();

            // Match libraryDependencies entries with test scope
            Pattern depPat =
                    Pattern.compile("\"([^\"]+)\"\\s*%{1,2}\\s*\"([^\"]+)\"\\s*%\\s*\"([^\"]+)\"\\s*(?:%\\s*(\\w+))?");
            Matcher m = depPat.matcher(content);

            boolean hasTestScope = false;
            while (m.find()) {
                String groupId = m.group(1).trim();
                String artifactId = m.group(2).trim();
                String version = m.group(3).trim();
                String scope = m.group(4) != null ? m.group(4).trim() : null;

                String lowerArtifact = artifactId.toLowerCase();
                boolean isTestFramework = false;
                String frameworkName = null;

                for (String known : KNOWN_TEST_FRAMEWORKS) {
                    if (lowerArtifact.contains(known)) {
                        isTestFramework = true;
                        frameworkName = known;
                        break;
                    }
                }

                if (isTestFramework || (scope != null && scope.equalsIgnoreCase("Test"))) {
                    Map<String, Object> fw = new LinkedHashMap<>();
                    fw.put("groupId", groupId);
                    fw.put("artifactId", artifactId);
                    fw.put("version", version);
                    if (frameworkName != null) fw.put("framework", frameworkName);
                    if (scope != null) fw.put("scope", scope);
                    frameworks.add(fw);
                    if (scope != null && scope.equalsIgnoreCase("Test")) hasTestScope = true;
                }
            }

            // Also detect test framework settings
            List<Map<String, String>> settings = new ArrayList<>();
            if (content.contains("testFrameworks")) {
                settings.add(Map.of("setting", "testFrameworks", "note", "Custom test framework ordering configured"));
            }
            if (content.contains("testOptions")) {
                settings.add(Map.of("setting", "testOptions", "note", "Custom test options configured"));
            }
            if (content.contains("Test / fork")) {
                settings.add(Map.of("setting", "Test / fork", "note", "Tests run in forked JVM"));
            }
            if (content.contains("Test / parallelExecution")) {
                settings.add(
                        Map.of("setting", "Test / parallelExecution", "note", "Parallel test execution configured"));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("project", dir.getFileName().toString());
            result.put("buildFile", "build.sbt");
            result.put("testFrameworks", frameworks);
            result.put("frameworkCount", frameworks.size());
            result.put("hasExplicitTestConfig", hasTestScope);
            if (!settings.isEmpty()) result.put("testSettings", settings);

            return toJson(result);
        } catch (IOException e) {
            return errorJson("Failed to read build.sbt: " + e.getMessage());
        }
    }

    /**
     * Analyze an SBT build file for plugins, settings, and structure.
     */
    @Tool(
            name = "analyze_sbt_build",
            description = "Analyze an SBT build.sbt for plugins, Scala version, "
                    + "resolvers, and other structural information. "
                    + "Useful for understanding project configuration without executing SBT.")
    public String analyzeSbtBuild(
            @ToolParam(required = true, description = "Project directory path containing build.sbt")
                    String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return errorJson("Cannot resolve project directory: " + e.getMessage());
        }

        Path buildFile = dir.resolve("build.sbt");
        if (!Files.exists(buildFile)) {
            return errorJson("No build.sbt found in: " + projectDir);
        }

        try {
            String content = Files.readString(buildFile);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("project", dir.getFileName().toString());
            result.put("buildFile", "build.sbt");

            // Scala version
            Pattern svPat = Pattern.compile("scalaVersion\\s*:=\\s*\"([^\"]+)\"");
            Matcher svm = svPat.matcher(content);
            if (svm.find()) result.put("scalaVersion", svm.group(1).trim());

            // SBT version
            Path props = dir.resolve("project/build.properties");
            if (Files.exists(props)) {
                String propsContent = Files.readString(props);
                Pattern sbtVerPat = Pattern.compile("sbt\\.version\\s*=\\s*([^\\s]+)");
                Matcher sbtVerM = sbtVerPat.matcher(propsContent);
                if (sbtVerM.find()) result.put("sbtVersion", sbtVerM.group(1).trim());
            }

            // Organization / name
            Pattern orgPat = Pattern.compile("organization\\s*:=\\s*\"([^\"]+)\"");
            Matcher orgm = orgPat.matcher(content);
            if (orgm.find()) result.put("organization", orgm.group(1).trim());

            Pattern namePat = Pattern.compile("name\\s*:=\\s*\"([^\"]+)\"");
            Matcher namem = namePat.matcher(content);
            if (namem.find()) result.put("name", namem.group(1).trim());

            // Detected plugins
            List<Map<String, String>> plugins = new ArrayList<>();
            for (String plugin : KNOWN_PLUGINS) {
                if (content.contains(plugin)) {
                    plugins.add(Map.of("plugin", plugin));
                }
            }
            if (!plugins.isEmpty()) result.put("detectedPlugins", plugins);

            // Resolvers
            List<String> resolvers = new ArrayList<>();
            Pattern resPat = Pattern.compile("resolvers\\s*[+]+=\\s*\"([^\"]+)\"\\s*at\\s*\"([^\"]+)\"");
            Matcher resm = resPat.matcher(content);
            while (resm.find()) {
                resolvers.add(resm.group(1).trim() + " @ " + resm.group(2).trim());
            }
            if (!resolvers.isEmpty()) result.put("customResolvers", resolvers);

            // Compiler options
            if (content.contains("scalacOptions")) {
                List<String> scalacOpts = new ArrayList<>();
                // Match scalacOptions += "-option" (single additions)
                Pattern scoPat = Pattern.compile("scalacOptions\\s*[+]+=\\s*\"([^\"]+)\"");
                Matcher scom = scoPat.matcher(content);
                while (scom.find()) scalacOpts.add(scom.group(1).trim());
                // Match scalacOptions ++= Seq("-opt1", "-opt2", ...)
                Pattern scoSeqPat = Pattern.compile("scalacOptions\\s*[+]+=\\s*Seq\\(([^)]+)\\)", Pattern.DOTALL);
                Matcher scoSeqM = scoSeqPat.matcher(content);
                while (scoSeqM.find()) {
                    for (String opt : scoSeqM.group(1).split(",")) {
                        opt = opt.trim().replace("\"", "");
                        if (!opt.isEmpty()) scalacOpts.add(opt);
                    }
                }
                if (!scalacOpts.isEmpty()) result.put("scalacOptions", scalacOpts);
            }

            // Cross-building
            if (content.contains("crossScalaVersions")) {
                Pattern crossPat = Pattern.compile("crossScalaVersions\\s*:=\\s*Seq\\(([^)]+)\\)");
                Matcher crossm = crossPat.matcher(content);
                if (crossm.find()) {
                    List<String> versions = new ArrayList<>();
                    for (String v : crossm.group(1).split(",")) {
                        versions.add(v.trim().replace("\"", ""));
                    }
                    result.put("crossScalaVersions", versions);
                }
            }

            return toJson(result);
        } catch (IOException e) {
            return errorJson("Failed to read build.sbt: " + e.getMessage());
        }
    }

    // ─── JSON helpers ────────────────────────────────────────────────────

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(e.getKey())).append("\":");
            appendVal(sb, e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendVal(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            sb.append("\"").append(esc((String) v)).append("\"");
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof List) {
            sb.append("[");
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(",");
                appendVal(sb, l.get(i));
            }
            sb.append("]");
        } else if (v instanceof Map) {
            sb.append("{");
            Map<String, Object> m = (Map<String, Object>) v;
            boolean f = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!f) sb.append(",");
                f = false;
                sb.append("\"").append(esc(e.getKey())).append("\":");
                appendVal(sb, e.getValue());
            }
            sb.append("}");
        } else {
            sb.append("\"").append(esc(String.valueOf(v))).append("\"");
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String errorJson(String msg) {
        return "{\"error\":true,\"message\":\"" + esc(msg) + "\"}";
    }
}
