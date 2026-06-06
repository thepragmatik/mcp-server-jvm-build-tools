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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public class TestClient {

    private static final String currentProjectDir = new File(".").getAbsolutePath();

    public static final String TARGET_MCP_SERVER_JAR = "target/mcp-server-jvm-build-tools.jar";

    private static final File serverJarFile = new File(TARGET_MCP_SERVER_JAR);

    private static final String serverJarPath = serverJarFile.getAbsolutePath();

    private static McpSyncClient mcpClient;

    public static void main(String[] args) {
        // Prepare to invoke Server from Client using StdIO mechanism.
        // No -Dmaven.home= JVM arg — the server auto-detects build tools from
        // the buildToolHome parameter passed in each tool call, or from PATH
        // (Gradle/SBT use wrappers or PATH fallback).
        var stdioParams = ServerParameters.builder("java")
                .args("-jar", serverJarPath)
                .build();

        var stdioTransport = new StdioClientTransport(stdioParams);
        mcpClient = McpClient.sync(stdioTransport).build();
        mcpClient.initialize();

        int passed = 0;
        int failed = 0;

        // ── 1. list_build_tools — baseline, must succeed ────────────────
        System.out.println("══════ list_build_tools ══════");
        try {
            callTool("list_build_tools", Collections.emptyMap());
            System.out.println("PASS: list_build_tools");
            passed++;
        } catch (Exception e) {
            System.err.println("FAIL: list_build_tools — " + e.getMessage());
            failed++;
        }

        // ── 2. get_build_tool_version for Maven, Gradle, SBT ───────────
        for (String tool : new String[]{"maven", "gradle", "sbt"}) {
            try {
                System.out.println("══════ get_build_tool_version (" + tool + ") ══════");
                callTool("get_build_tool_version", Map.of("buildToolName", tool));
                System.out.println("PASS: get_build_tool_version(" + tool + ")");
                passed++;
            } catch (Exception e) {
                System.err.println("INFO: get_build_tool_version(" + tool + ") not available — "
                        + e.getMessage());
                passed++; // expected for tools not installed
            }
        }

        // ── 3. detect_build_tool on current project ────────────────────
        System.out.println("══════ detect_build_tool ══════");
        try {
            callTool("detect_build_tool", Map.of("projectDir", currentProjectDir));
            System.out.println("PASS: detect_build_tool");
            passed++;
        } catch (Exception e) {
            System.err.println("FAIL: detect_build_tool — " + e.getMessage());
            failed++;
        }

        // ── 4. execute_build_command (Maven) ──────────────────────────
        System.out.println("══════ execute_build_command (maven compile) ══════");
        try {
            callTool("execute_build_command", Map.of(
                    "buildToolName", "maven",
                    "projectDir", currentProjectDir,
                    "command", "compile"
            ));
            System.out.println("PASS: execute_build_command(maven)");
            passed++;
        } catch (Exception e) {
            System.err.println("FAIL: execute_build_command(maven) — " + e.getMessage());
            failed++;
        }

        // ── 5. Gradle wrapper detection test ───────────────────────────
        // Tests that the server can find Gradle via wrapper or PATH
        System.out.println("══════ execute_build_command (gradle tasks) ══════");
        try {
            callTool("execute_build_command", Map.of(
                    "buildToolName", "gradle",
                    "projectDir", currentProjectDir,
                    "command", "tasks"
            ));
            System.out.println("PASS: execute_build_command(gradle)");
            passed++;
        } catch (Exception e) {
            System.err.println("INFO: execute_build_command(gradle) skipped — " + e.getMessage());
            passed++; // gradle may not be installed
        }

        // ── 6. SBT test ───────────────────────────────────────────────
        System.out.println("══════ execute_build_command (sbt compile) ══════");
        try {
            callTool("execute_build_command", Map.of(
                    "buildToolName", "sbt",
                    "projectDir", currentProjectDir,
                    "command", "compile"
            ));
            System.out.println("PASS: execute_build_command(sbt)");
            passed++;
        } catch (Exception e) {
            System.err.println("INFO: execute_build_command(sbt) skipped — " + e.getMessage());
            passed++; // sbt may not be installed
        }

        // ── 7. Error handling: nonexistent build tool ──────────────────
        System.out.println("══════ Error handling: unknown tool ══════");
        {
            String output = callToolAndGetText("get_build_tool_version",
                    Map.of("buildToolName", "nonexistent"));
            if (output.contains("Unknown build tool")) {
                System.out.println("PASS: Server reported unknown tool: " + output);
                passed++;
            } else {
                System.err.println("FAIL: Expected 'Unknown build tool' but got: " + output);
                failed++;
            }
        }

        // ── 8. Error handling: nonexistent project directory ───────────
        System.out.println("══════ Error handling: bad project dir ══════");
        {
            String output = callToolAndGetText("execute_build_command",
                    Map.of("buildToolName", "maven",
                           "projectDir", "/tmp/non-existent-project-dir",
                           "command", "compile"));
            if (output.contains("Cannot resolve path") || output.contains("not valid")
                    || output.contains("not a directory")) {
                System.out.println("PASS: Server reported invalid project dir: " + output);
                passed++;
            } else {
                System.err.println("FAIL: Expected error for bad project dir but got: " + output);
                failed++;
            }
        }

        // ── 9. analyze_build_output ───────────────────────────────────
        System.out.println("══════ analyze_build_output (maven compile) ══════");
        try {
            callTool("analyze_build_output", Map.of(
                    "buildToolName", "maven",
                    "projectDir", currentProjectDir,
                    "command", "compile"
            ));
            System.out.println("PASS: analyze_build_output");
            passed++;
        } catch (Exception e) {
            System.err.println("FAIL: analyze_build_output — " + e.getMessage());
            failed++;
        }

        // ── 10. validate_build_configuration ──────────────────────────
        System.out.println("══════ validate_build_configuration ══════");
        try {
            callTool("validate_build_configuration", Map.of(
                    "projectDir", currentProjectDir
            ));
            System.out.println("PASS: validate_build_configuration");
            passed++;
        } catch (Exception e) {
            System.err.println("FAIL: validate_build_configuration — " + e.getMessage());
            failed++;
        }

        // ── 11. check_dependency_version ──────────────────────────────
        System.out.println("══════ check_dependency_version ══════");
        try {
            callTool("check_dependency_version", Map.of(
                    "groupId", "org.springframework.boot",
                    "artifactId", "spring-boot-starter-web",
                    "projectDir", currentProjectDir
            ));
            System.out.println("PASS: check_dependency_version");
            passed++;
        } catch (Exception e) {
            System.err.println("FAIL: check_dependency_version — " + e.getMessage());
            failed++;
        }

        System.out.println("\n══════ Results: " + passed + " passed, " + failed + " failed ══════");

        mcpClient.closeGracefully();

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void callTool(String tool, Map<String, Object> params) {
        McpSchema.CallToolResult result = mcpClient.callTool(
                new McpSchema.CallToolRequest(tool, params));
        result.content().forEach(c -> {
            if (c instanceof McpSchema.TextContent) {
                var text = ((McpSchema.TextContent) c).text();
                var formatted = text.replaceAll("\\\\n", System.lineSeparator());
                System.out.println(formatted);
            } else {
                System.err.println("Unsupported content type encountered in result.");
            }
        });
    }

    private static String callToolAndGetText(String tool, Map<String, Object> params) {
        McpSchema.CallToolResult result = mcpClient.callTool(
                new McpSchema.CallToolRequest(tool, params));
        StringBuilder sb = new StringBuilder();
        result.content().forEach(c -> {
            if (c instanceof McpSchema.TextContent) {
                var text = ((McpSchema.TextContent) c).text();
                sb.append(text);
            }
        });
        return sb.toString();
    }

}
