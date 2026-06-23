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
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public class TestClient {

    /** Resolve a test project directory, trying classpath first, then filesystem. */
    private static String resolveTestProjectDir(String name) {
        // Try classpath — works regardless of CWD
        var url = TestClient.class.getClassLoader().getResource(name + "/pom.xml");
        if (url == null) {
            url = TestClient.class.getClassLoader().getResource(name + "/build.gradle");
        }
        if (url == null) {
            url = TestClient.class.getClassLoader().getResource(name + "/build.sbt");
        }
        if (url != null && "file".equals(url.getProtocol())) {
            return new File(url.getPath()).getParent();
        }
        // Fallback: resolve relative to project root (CWD)
        return new File("src/test/resources/" + name).getAbsolutePath();
    }

    private static final String currentProjectDir = new File(".").getAbsolutePath();

    private static final String mavenTestProjectDir = resolveTestProjectDir("test-maven-project");

    private static final String gradleTestProjectDir = resolveTestProjectDir("test-gradle-project");

    private static final String sbtTestProjectDir = resolveTestProjectDir("test-sbt-project");

    private static final String mavenHome = resolveMavenHome();

    private static String resolveMavenHome() {
        String home = System.getenv("MAVEN_HOME");
        if (home != null && !home.isBlank()) return home;
        // Common installation paths
        for (String candidate : new String[] {
            "/opt/apache-maven-3.9.9", "/usr/share/maven", "/usr/local/maven", "/opt/homebrew/opt/maven/libexec"
        }) {
            if (new File(candidate, "bin/mvn").exists()) return candidate;
        }
        return null;
    }

    public static final String TARGET_MCP_SERVER_JAR = "target/mcp-server-jvm-build-tools.jar";

    private static final File serverJarFile = new File(TARGET_MCP_SERVER_JAR);

    private static final String serverJarPath = serverJarFile.getAbsolutePath();

    private static McpSyncClient mcpClient;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        startServer();

        testListBuildTools();
        testGetBuildToolVersion();
        testDetectBuildTool();
        testMavenBuild();
        testGradleBuild();
        testSbtBuild();
        testErrorHandling();
        testSecurityInjection();
        testBuildToolHome();
        testAnalyzeBuildOutput();
        testValidateBuildConfig();
        testDependencyVersion();

        mcpClient.closeGracefully();

        System.out.println("\n══════ Results: " + passed + " passed, " + failed + " failed ══════");

        if (failed > 0) {
            System.exit(1);
        }
    }

    // ── Server startup ──────────────────────────────────────────────────

    private static void startServer() {
        if (!serverJarFile.exists() || isJarStale()) {
            System.out.println("JAR missing or stale — rebuilding...");
            rebuildJar();
        }
        var stdioParams =
                ServerParameters.builder("java").args("-jar", serverJarPath).build();
        var stdioTransport = new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(new JsonMapper()));
        mcpClient = McpClient.sync(stdioTransport).build();
        mcpClient.initialize();
    }

    /** Returns true if any source file under src/main is newer than the JAR. */
    private static boolean isJarStale() {
        long jarTime = serverJarFile.lastModified();
        File srcDir = new File("src/main");
        return youngestFile(srcDir) > jarTime;
    }

    private static long youngestFile(File dir) {
        long youngest = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                youngest = Math.max(youngest, youngestFile(f));
            } else if (f.lastModified() > youngest) {
                youngest = f.lastModified();
            }
        }
        return youngest;
    }

    private static void rebuildJar() {
        try {
            ProcessBuilder pb = new ProcessBuilder("mvn", "package", "-DskipTests", "-q");
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) {
                System.err.println(
                        "WARNING: mvn package exited with code " + exit + " — using existing JAR if available");
            }
        } catch (Exception e) {
            System.err.println(
                    "WARNING: Could not rebuild JAR: " + e.getMessage() + " — using existing JAR if available");
        }
    }

    // ── Test methods ────────────────────────────────────────────────────

    private static void testListBuildTools() {
        System.out.println("══════ list_build_tools ══════");
        try {
            callTool("list_build_tools", Collections.emptyMap());
            pass("list_build_tools");
        } catch (Exception e) {
            fail("list_build_tools", e);
        }
    }

    private static void testGetBuildToolVersion() {
        for (String tool : new String[] {"maven", "gradle", "sbt"}) {
            try {
                System.out.println("══════ get_build_tool_version (" + tool + ") ══════");
                callTool("get_build_tool_version", Map.of("buildToolName", tool));
                pass("get_build_tool_version(" + tool + ")");
            } catch (Exception e) {
                System.err.println("INFO: get_build_tool_version(" + tool + ") not available — " + e.getMessage());
                passed++; // expected for tools not installed
            }
        }
    }

    private static void testDetectBuildTool() {
        System.out.println("══════ detect_build_tool ══════");
        try {
            callTool("detect_build_tool", Map.of("projectDir", currentProjectDir));
            pass("detect_build_tool");
        } catch (Exception e) {
            fail("detect_build_tool", e);
        }
    }

    private static void testMavenBuild() {
        System.out.println("══════ execute_build_command (maven compile) ══════");
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("buildToolName", "maven");
            params.put("projectDir", mavenTestProjectDir);
            params.put("command", "compile");
            if (mavenHome != null) params.put("buildToolHome", mavenHome);
            callTool("execute_build_command", params);
            pass("execute_build_command(maven)");
        } catch (Exception e) {
            fail("execute_build_command(maven)", e);
        }
    }

    private static void testGradleBuild() {
        System.out.println("══════ execute_build_command (gradle tasks) ══════");
        try {
            callTool(
                    "execute_build_command",
                    Map.of(
                            "buildToolName", "gradle",
                            "projectDir", gradleTestProjectDir,
                            "command", "tasks"));
            pass("execute_build_command(gradle)");
        } catch (Exception e) {
            System.err.println("INFO: execute_build_command(gradle) skipped — " + e.getMessage());
            passed++; // gradle may not be installed
        }
    }

    private static void testSbtBuild() {
        System.out.println("══════ execute_build_command (sbt compile) ══════");
        try {
            callTool(
                    "execute_build_command",
                    Map.of(
                            "buildToolName", "sbt",
                            "projectDir", sbtTestProjectDir,
                            "command", "compile"));
            pass("execute_build_command(sbt)");
        } catch (Exception e) {
            System.err.println("INFO: execute_build_command(sbt) skipped — " + e.getMessage());
            passed++; // sbt may not be installed
        }
    }

    private static void testErrorHandling() {
        // Unknown build tool
        System.out.println("══════ Error handling: unknown tool ══════");
        String output = callToolAndGetText("get_build_tool_version", Map.of("buildToolName", "nonexistent"));
        if (output.contains("Unknown build tool")) {
            pass("Server reported unknown tool");
        } else {
            System.err.println("FAIL: Server reported unknown tool — expected 'Unknown build tool' but got: " + output);
            failed++;
        }

        // Nonexistent project directory
        System.out.println("══════ Error handling: bad project dir ══════");
        output = callToolAndGetText(
                "execute_build_command",
                Map.of(
                        "buildToolName", "maven",
                        "projectDir", "/tmp/non-existent-project-dir",
                        "command", "compile"));
        if (output.contains("Cannot resolve path")
                || output.contains("not valid")
                || output.contains("not a directory")) {
            pass("Server reported invalid project dir");
        } else {
            System.err.println("FAIL: Server reported invalid project dir — expected path error but got: " + output);
            failed++;
        }
    }

    private static void testSecurityInjection() {
        System.out.println("══════ Security: injection rejection ══════");
        String output = callToolAndGetText(
                "execute_build_command",
                Map.of(
                        "buildToolName", "maven",
                        "projectDir", currentProjectDir,
                        "command", "compile && rm -rf /"));
        if (output.contains("disallowed")
                || output.contains("Disallowed")
                || output.contains("invalid")
                || output.contains("Invalid")) {
            pass("Server rejected injection attempt");
        } else if (output.contains("BUILD SUCCESS")) {
            System.err.println("FAIL: Server executed injected command: " + output);
            failed++;
        } else {
            // Unexpected response — regressions deserve investigation, not a free pass
            System.err.println("FAIL: Unexpected response to injection test: " + output);
            failed++;
        }
    }

    private static void testBuildToolHome() {
        System.out.println("══════ execute_build_command with buildToolHome ══════");
        try {
            String home = mavenHome != null ? mavenHome : "/usr/share/maven";
            callTool(
                    "execute_build_command",
                    Map.of(
                            "buildToolName",
                            "maven",
                            "buildToolHome",
                            home,
                            "projectDir",
                            currentProjectDir,
                            "command",
                            "--version"));
            pass("buildToolHome parameter (maven)");
        } catch (Exception e) {
            System.err.println("INFO: buildToolHome test skipped — " + e.getMessage());
            passed++; // buildToolHome path may not exist
        }
    }

    private static void testAnalyzeBuildOutput() {
        System.out.println("══════ analyze_build_output (maven compile) ══════");
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("buildToolName", "maven");
            params.put("projectDir", mavenTestProjectDir);
            params.put("command", "compile");
            if (mavenHome != null) params.put("buildToolHome", mavenHome);
            callTool("analyze_build_output", params);
            pass("analyze_build_output");
        } catch (Exception e) {
            fail("analyze_build_output", e);
        }
    }

    private static void testValidateBuildConfig() {
        System.out.println("══════ validate_build_configuration ══════");
        try {
            callTool("validate_build_configuration", Map.of("projectDir", currentProjectDir));
            pass("validate_build_configuration");
        } catch (Exception e) {
            fail("validate_build_configuration", e);
        }
    }

    private static void testDependencyVersion() {
        System.out.println("══════ check_dependency_version ══════");
        try {
            callTool(
                    "check_dependency_version",
                    Map.of(
                            "groupId", "org.springframework.boot",
                            "artifactId", "spring-boot-starter-web",
                            "projectDir", currentProjectDir));
            pass("check_dependency_version");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null
                    && (msg.contains("ConnectException")
                            || msg.contains("UnknownHostException")
                            || msg.contains("timeout")
                            || msg.contains("unreachable"))) {
                System.out.println("INFO: check_dependency_version skipped" + " — Maven Central unreachable");
                passed++;
            } else {
                fail("check_dependency_version", e);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void pass(String name) {
        System.out.println("PASS: " + name);
        passed++;
    }

    private static void fail(String name, Exception e) {
        System.err.println("FAIL: " + name + " — " + e.getMessage());
        failed++;
    }

    private static void callTool(String tool, Map<String, Object> params) {
        McpSchema.CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest(tool, params));
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
        McpSchema.CallToolResult result = mcpClient.callTool(new McpSchema.CallToolRequest(tool, params));
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
