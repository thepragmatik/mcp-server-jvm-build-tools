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
import java.util.Arrays;
import java.util.Map;

public class TestClient {

    private static final String userHome = System.getProperty("user.home");

    // XXX: May need to update Maven Home path according to your system.
    private static final String mavenHome = new File(userHome,
            "/.sdkman/candidates/maven/current").getAbsolutePath();

    private static final String currentProjectDir = new File(".").getAbsolutePath();

    public static final String TARGET_MCP_SERVER__JAR = "target/mcp-server-build-tools-jvm.jar";

    private static final File serverJarFile = new File(TARGET_MCP_SERVER__JAR);

    private static final String serverJarPath = serverJarFile.getAbsolutePath();

    private static McpSyncClient mcpClient;

    public static void main(String[] args) {
        // Prepare to invoke Server from Client using StdIO mech
        var stdioParams = ServerParameters.builder("java")
                .args(
                        "-jar",
                        "-Dmaven.home=" + mavenHome,
                        serverJarPath
                ).build();

        var stdioTransport = new StdioClientTransport(stdioParams);
        mcpClient = McpClient.sync(stdioTransport).build();
        mcpClient.initialize();

//        McpSchema.ListToolsResult toolsList = mcpClient.listTools();
//        System.out.println("Available Build tools: " + toolsList);

        String[][] toolCalls = new String[][]{
                {"get_maven_version", ""},
                {"execute_maven_command", "mvn clean compile test package"},
                {"execute_maven_command", "exec:exec -Dexec.executable=/bin/pwd"}   // without explicit "mvn"
        };

        Arrays.stream(toolCalls).forEach(toolCall -> {
            String tool = toolCall[0];
            String params = toolCall[1];
            var result = invokeTool(mavenHome, currentProjectDir, tool, params);
            result.content().forEach(c -> {
                if (c instanceof McpSchema.TextContent) {
                    var text = ((McpSchema.TextContent) c).text();
                    var formatted = text.replaceAll("\\\\n", System.lineSeparator());
                    System.out.println("===== Tool call result below =====");
                    System.err.println(formatted);
                } else {
                    System.err.println("Unsupported content type encountered in result.");
                }
            });
        });

        // try an error scenario
        // with none-existent project directory
        McpSchema.CallToolResult result = invokeTool(mavenHome, "/tmp/non-existent-project-dir",
                "execute_maven_command",
                "mvn clean package");

        result.content().forEach(c -> {
            if (c instanceof McpSchema.TextContent) {
                var text = ((McpSchema.TextContent) c).text();
                var formatted = text.replaceAll("\\\\n", System.lineSeparator());
                System.out.println("===== Tool call result below =====");
                System.err.println(formatted);
            }
        });

        mcpClient.closeGracefully();
    }

    private static McpSchema.CallToolResult invokeTool(String mHome, String projectDir, String tool, String command) {
        McpSchema.CallToolResult result;
        result = mcpClient.callTool(new McpSchema.CallToolRequest(tool,
                Map.of(
                        "mavenHome", mHome,
                        "projectDir", projectDir,
                        "command", command
                )
        ));
        return result;
    }
}
