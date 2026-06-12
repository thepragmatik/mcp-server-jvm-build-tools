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

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MCP resource template service.
 * <p>
 * Exposes URI templates following the MCP resource template pattern,
 * enabling LLM clients to discover and construct dynamic resource URIs.
 * Unlike {@link BuildResourceService} which provides static URIs for a
 * specific project, templates allow parameterization with placeholders
 * like {@code {projectName}} and {@code {buildTool}}.
 * <p>
 * Template URIs follow the scheme:
 * <ul>
 *   <li>{@code build://{projectName}/dependencies/{buildTool}} — per-tool dependencies</li>
 *   <li>{@code build://{projectName}/config/{fileName}} — specific config files</li>
 *   <li>{@code build://{projectName}/logs/{buildCommand}} — build command logs</li>
 *   <li>{@code build://{projectName}/test-results/{testSuite}} — test suite results</li>
 *   <li>{@code build://{projectName}/summary} — project build summary</li>
 * </ul>
 */
@Service
public class ResourceTemplateService {

    @Tool(name = "list_resource_templates",
          description = "List all available MCP resource templates with their URI patterns and " +
                        "parameter descriptions. Templates allow dynamic resource URI construction " +
                        "through parameter substitution.")
    public String listResourceTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();

        // Template 1: Dependencies per build tool
        templates.add(buildTemplate(
            "build://{projectName}/dependencies/{buildTool}",
            "Project Dependencies by Build Tool",
            "Dependency declarations extracted from a specific build tool's file. " +
            "Supports: maven, gradle, sbt.",
            List.of(
                param("projectName", "string", "The project directory name"),
                param("buildTool", "string", "Build tool: 'maven', 'gradle', or 'sbt'")
            ),
            "application/json"
        ));

        // Template 2: Specific config file
        templates.add(buildTemplate(
            "build://{projectName}/config/{fileName}",
            "Build Configuration File",
            "Read a specific build configuration file by name. " +
            "Examples: pom.xml, build.gradle, build.gradle.kts, build.sbt, settings.gradle.",
            List.of(
                param("projectName", "string", "The project directory name"),
                param("fileName", "string", "Build file name (e.g., 'pom.xml', 'build.gradle.kts')")
            ),
            "text/plain"
        ));

        // Template 3: Build logs by command
        templates.add(buildTemplate(
            "build://{projectName}/logs/{buildCommand}",
            "Build Command Logs",
            "Output logs from a specific build command. " +
            "Examples: compile, test, package, install, clean.",
            List.of(
                param("projectName", "string", "The project directory name"),
                param("buildCommand", "string", "Build command (e.g., 'compile', 'test', 'package')")
            ),
            "text/plain"
        ));

        // Template 4: Test results by suite
        templates.add(buildTemplate(
            "build://{projectName}/test-results/{testSuite}",
            "Test Suite Results",
            "Structured test results for a specific test suite or class. " +
            "Use 'all' to get results for all test suites.",
            List.of(
                param("projectName", "string", "The project directory name"),
                param("testSuite", "string", "Test suite name or 'all' for all suites")
            ),
            "application/json"
        ));

        // Template 5: Project summary
        templates.add(buildTemplate(
            "build://{projectName}/summary",
            "Project Build Summary",
            "Aggregated summary of build configuration, dependencies, " +
            "last build status, and detected build tool.",
            List.of(
                param("projectName", "string", "The project directory name")
            ),
            "application/json"
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCount", templates.size());
        result.put("templateScheme", "build://{projectName}/...");
        result.put("description", "MCP resource templates for dynamic resource URI construction");
        result.put("templates", templates);

        return JsonUtils.toJson(result);
    }

    @Tool(name = "resolve_resource_template",
          description = "Resolve a resource template URI by substituting parameter values. " +
                        "Returns the concrete resource URI and validates parameter constraints.")
    public String resolveResourceTemplate(
            @ToolParam(required = true, description = "Template URI pattern (e.g., 'build://{projectName}/dependencies/{buildTool}')")
            String templateUri,
            @ToolParam(required = true, description = "Parameter values as JSON object (e.g., '{\"projectName\":\"myapp\",\"buildTool\":\"maven\"}')")
            String paramsJson) {

        Map<String, Object> params;
        try {
            params = parseSimpleJson(paramsJson);
        } catch (Exception e) {
            return JsonUtils.errorJson("Invalid params JSON: " + e.getMessage());
        }

        // Validate known templates
        String resolved = templateUri;
        List<String> missingParams = new ArrayList<>();
        List<String> usedParams = new ArrayList<>();

        // Extract parameter names from template
        java.util.regex.Pattern placeholderPattern = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
        java.util.regex.Matcher m = placeholderPattern.matcher(templateUri);
        StringBuilder resolvedBuilder = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            resolvedBuilder.append(templateUri, lastEnd, m.start());
            String paramName = m.group(1);
            Object value = params.get(paramName);
            if (value != null) {
                resolvedBuilder.append(String.valueOf(value));
                usedParams.add(paramName);
            } else {
                resolvedBuilder.append("{").append(paramName).append("}");
                missingParams.add(paramName);
            }
            lastEnd = m.end();
        }
        resolvedBuilder.append(templateUri.substring(lastEnd));
        resolved = resolvedBuilder.toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateUri", templateUri);
        result.put("resolved", !missingParams.isEmpty() ? null : resolved);
        result.put("allParamsResolved", missingParams.isEmpty());
        if (!missingParams.isEmpty()) {
            result.put("missingParams", missingParams);
            result.put("hint", "Provide values for all missing parameters to resolve the URI");
        }
        result.put("paramsProvided", usedParams);
        result.put("paramCount", params.size());

        return JsonUtils.toJson(result);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static Map<String, Object> buildTemplate(String uri, String name,
                                                      String description,
                                                      List<Map<String, Object>> params,
                                                      String mimeType) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("uriTemplate", uri);
        t.put("name", name);
        t.put("description", description);
        t.put("mimeType", mimeType);
        t.put("parameters", params);
        return t;
    }

    private static Map<String, Object> param(String name, String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("type", type);
        p.put("required", true);
        p.put("description", description);
        return p;
    }

    /**
     * Simple JSON object parser — no Jackson dependency needed.
     * Handles flat objects with string values. Example: {"key":"val","key2":"val2"}
     */
    private static Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Expected JSON object wrapped in { }");
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return map;

        // Split by comma, respecting quoted strings
        List<String> pairs = splitJsonPairs(inner);
        for (String pair : pairs) {
            int colon = findUnquotedColon(pair);
            if (colon == -1) continue;
            String key = unquote(pair.substring(0, colon).trim());
            String val = unquote(pair.substring(colon + 1).trim());
            map.put(key, val);
        }
        return map;
    }

    private static List<String> splitJsonPairs(String s) {
        List<String> pairs = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            if (c == '}' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                pairs.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < s.length()) {
            pairs.add(s.substring(start).trim());
        }
        return pairs;
    }

    private static int findUnquotedColon(String s) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString && c == ':') return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
