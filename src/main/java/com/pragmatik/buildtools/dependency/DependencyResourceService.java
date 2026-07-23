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
package com.pragmatik.buildtools.dependency;

import com.pragmatik.buildtools.build.BuildTool;
import com.pragmatik.buildtools.build.BuildToolProvider;
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
 * Multi-build-tool dependency extraction service.
 * <p>
 * Extracts dependency declarations from Maven (pom.xml), Gradle
 * (build.gradle / build.gradle.kts), and SBT (build.sbt) build files
 * and exposes them through MCP tools.
 * <p>
 * Each tool returns structured JSON with resource URIs following the
 * {@code build://{project}/dependencies} scheme, enabling MCP clients
 * to navigate project dependencies across build systems.
 */
@Service
public class DependencyResourceService {

    private final BuildToolProvider toolProvider;

    public DependencyResourceService(BuildToolProvider toolProvider) {
        this.toolProvider = toolProvider;
    }

    @Tool(
            name = "list_dependency_resources",
            description = "List available dependency resources for a project directory. "
                    + "Returns resource URIs with dependency counts per build tool.")
    public String listDependencyResources(
            @ToolParam(required = true, description = "Project directory path") String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return errorJson("Cannot resolve project directory: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) {
            return errorJson("Project directory is not valid: " + projectDir);
        }

        String projectName = dir.getFileName().toString();
        BuildTool detected = toolProvider.resolve(null, dir);

        List<Map<String, Object>> resources = new ArrayList<>();

        if (Files.exists(dir.resolve("pom.xml"))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uri", "build://" + projectName + "/dependencies/maven");
            r.put("name", "Maven Dependencies");
            r.put("description", "Dependencies declared in pom.xml");
            r.put("mimeType", "application/json");
            r.put("buildTool", "maven");
            resources.add(r);
        }

        for (String gf : new String[] {"build.gradle", "build.gradle.kts"}) {
            if (Files.exists(dir.resolve(gf))) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("uri", "build://" + projectName + "/dependencies/gradle");
                r.put("name", "Gradle Dependencies");
                r.put("description", "Dependencies declared in " + gf);
                r.put("mimeType", "application/json");
                r.put("buildTool", "gradle");
                resources.add(r);
                break;
            }
        }

        if (Files.exists(dir.resolve("build.sbt"))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uri", "build://" + projectName + "/dependencies/sbt");
            r.put("name", "SBT Dependencies");
            r.put("description", "Dependencies declared in build.sbt");
            r.put("mimeType", "application/json");
            r.put("buildTool", "sbt");
            resources.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", projectName);
        result.put("projectDir", dir.toString());
        result.put("detectedTool", detected.getName());
        result.put("resourceCount", resources.size());
        result.put("resources", resources);

        return toJson(result);
    }

    @Tool(
            name = "read_dependency_resource",
            description = "Read extracted dependencies from a build file. "
                    + "Use list_dependency_resources first to discover available URIs.")
    public String readDependencyResource(
            @ToolParam(required = true, description = "Resource URI (e.g., 'build://myproject/dependencies/maven')")
                    String uri,
            @ToolParam(required = true, description = "Project directory path") String projectDir) {

        Path dir;
        try {
            dir = Path.of(projectDir).toRealPath();
        } catch (IOException e) {
            return errorJson("Cannot resolve project directory: " + e.getMessage());
        }

        String projectName = dir.getFileName().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uri", uri);
        result.put("project", projectName);

        if (uri.endsWith("/dependencies/maven") && Files.exists(dir.resolve("pom.xml"))) {
            return extractMavenDependencies(dir, result);
        } else if (uri.endsWith("/dependencies/gradle")) {
            Path gf = findGradleFile(dir);
            if (gf != null) return extractGradleDependencies(gf, result);
            result.put("available", false);
            result.put("error", "No Gradle build file found");
            return toJson(result);
        } else if (uri.endsWith("/dependencies/sbt") && Files.exists(dir.resolve("build.sbt"))) {
            return extractSbtDependencies(dir, result);
        } else {
            return errorJson("Unknown resource URI: " + uri);
        }
    }

    // ─── Maven ──────────────────────────────────────────────────────────

    private String extractMavenDependencies(Path dir, Map<String, Object> result) {
        try {
            String content = Files.readString(dir.resolve("pom.xml"));
            List<Map<String, Object>> deps = new ArrayList<>();

            Pattern dp = Pattern.compile(
                    "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>"
                            + "\\s*(?:<version>([^<]*)</version>)?(?:\\s*<scope>([^<]*)</scope>)?",
                    Pattern.DOTALL);
            Matcher m = dp.matcher(content);
            while (m.find()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("groupId", m.group(1).trim());
                d.put("artifactId", m.group(2).trim());
                String v = m.group(3);
                d.put("version", v != null && !v.isBlank() ? v.trim() : "[managed]");
                String scope = m.group(4);
                d.put("scope", scope != null && !scope.isBlank() ? scope.trim() : "compile");
                d.put("buildTool", "maven");
                deps.add(d);
            }

            result.put("buildTool", "maven");
            result.put("buildFile", "pom.xml");
            result.put("contentType", "application/json");
            result.put("dependencyCount", deps.size());
            result.put("dependencies", deps);
            result.put("available", !deps.isEmpty());
            return toJson(result);
        } catch (IOException e) {
            result.put("available", false);
            result.put("error", "Failed to read pom.xml: " + e.getMessage());
            return toJson(result);
        }
    }

    // ─── Gradle ─────────────────────────────────────────────────────────

    private Path findGradleFile(Path dir) {
        for (String n : new String[] {"build.gradle.kts", "build.gradle"}) {
            Path f = dir.resolve(n);
            if (Files.exists(f)) return f;
        }
        return null;
    }

    private String extractGradleDependencies(Path gf, Map<String, Object> result) {
        try {
            String content = Files.readString(gf);
            List<Map<String, Object>> deps = new ArrayList<>();
            boolean kts = gf.getFileName().toString().endsWith(".kts");

            // Kotlin DSL: implementation("group:artifact:version")
            // Groovy DSL: implementation 'group:artifact:version'
            Pattern p;
            if (kts) {
                p = Pattern.compile("(\\w+)\\s*\\(\\s*\"([^:\"]+):([^:\"]+):([^\"]+)\"\\s*\\)");
            } else {
                p = Pattern.compile("(\\w+)\\s+'([^:']+):([^:']+):([^']+)'");
            }

            Matcher m = p.matcher(content);
            while (m.find()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("configuration", m.group(1).trim());
                d.put("groupId", m.group(2).trim());
                d.put("artifactId", m.group(3).trim());
                d.put("version", m.group(4).trim());
                d.put("buildTool", "gradle");
                d.put("declarationFormat", kts ? "kotlin-dsl" : "groovy-dsl");
                deps.add(d);
            }

            result.put("buildTool", "gradle");
            result.put("buildFile", gf.getFileName().toString());
            result.put("declarationStyle", kts ? "kotlin-dsl" : "groovy-dsl");
            result.put("contentType", "application/json");
            result.put("dependencyCount", deps.size());
            result.put("dependencies", deps);
            result.put("available", !deps.isEmpty());
            return toJson(result);
        } catch (IOException e) {
            result.put("available", false);
            result.put("error", "Failed to read " + gf.getFileName() + ": " + e.getMessage());
            return toJson(result);
        }
    }

    // ─── SBT ────────────────────────────────────────────────────────────

    private String extractSbtDependencies(Path dir, Map<String, Object> result) {
        try {
            String content = Files.readString(dir.resolve("build.sbt"));
            List<Map<String, Object>> deps = new ArrayList<>();

            Pattern p = Pattern.compile("\"([^\"]+)\"\\s*(%{1,2})\\s*\"([^\"]+)\"\\s*%\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(content);
            while (m.find()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("groupId", m.group(1).trim());
                d.put("artifactId", m.group(3).trim());
                d.put("version", m.group(4).trim());
                d.put("scalaVersioned", "%%".equals(m.group(2)));
                d.put("buildTool", "sbt");
                deps.add(d);
            }

            // Extract scalaVersion
            Pattern sv = Pattern.compile("scalaVersion\\s*:=\\s*\"([^\"]+)\"");
            Matcher svm = sv.matcher(content);
            if (svm.find()) result.put("scalaVersion", svm.group(1).trim());

            result.put("buildTool", "sbt");
            result.put("buildFile", "build.sbt");
            result.put("contentType", "application/json");
            result.put("dependencyCount", deps.size());
            result.put("dependencies", deps);
            result.put("available", !deps.isEmpty());
            return toJson(result);
        } catch (IOException e) {
            result.put("available", false);
            result.put("error", "Failed to read build.sbt: " + e.getMessage());
            return toJson(result);
        }
    }

    // ─── JSON ───────────────────────────────────────────────────────────

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
