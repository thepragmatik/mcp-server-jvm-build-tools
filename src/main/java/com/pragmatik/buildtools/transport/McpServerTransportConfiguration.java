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
package com.pragmatik.buildtools.transport;

import com.pragmatik.buildtools.application.McpServerIdentity;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual MCP server transport auto-configuration.
 * <p>
 * Spring AI 2.0.0 {@code spring-ai-mcp} provides client-side tool callbacks
 * and model classes but does NOT include server-side transport auto-configuration.
 * The MCP SDK 2.0.0 has {@link StdioServerTransportProvider} but no
 * {@code McpServer} / {@code McpServerTransportProvider} beans are registered
 * automatically. This class bridges that gap by wiring the server manually.
 * <p>
 * Activation is controlled by {@code spring.ai.mcp.server.stdio=true} (the default).
 * When disabled, no MCP server beans are created, and the Spring context starts
 * without a transport listener (useful for HTTP-only or test profiles).
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "true", matchIfMissing = true)
public class McpServerTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpServerTransportConfiguration.class);

    /**
     * Jackson {@code tools.jackson.databind.json.JsonMapper} bean.
     * <p>
     * Spring Boot 4.1.0 auto-configures a {@code JsonMapper} via
     * {@code JacksonAutoConfiguration}. The MCP SDK 2.0.0's
     * {@link JacksonMcpJsonMapper} wraps this same mapper type, so
     * injecting Spring Boot's auto-configured instance is both simple and
     * consistent with any user-provided Jackson customisation.
     */
    @Bean
    public JsonMapper mcpJsonMapperBean() {
        return new JsonMapper();
    }

    /**
     * Bridge the MCP SDK's {@link McpJsonMapper} to our {@link JsonMapper}.
     */
    @Bean
    public McpJsonMapper mcpJsonMapper(JsonMapper jsonMapper) {
        return new JacksonMcpJsonMapper(jsonMapper);
    }

    /**
     * Stdio transport provider — reads JSON-RPC from {@code System.in},
     * writes responses to {@code System.out}.
     */
    @Bean
    public McpServerTransportProvider stdioServerTransportProvider(McpJsonMapper jsonMapper) {
        return new StdioServerTransportProvider(jsonMapper);
    }

    /**
     * The MCP sync server wired with all {@code @Tool}-annotated methods
     * from the application's {@link ToolCallbackProvider}.
     */
    @Bean
    public McpSyncServer mcpSyncServer(
            McpServerTransportProvider transportProvider,
            McpJsonMapper jsonMapper,
            McpServerIdentity identity,
            ToolCallbackProvider toolCallbackProvider,
            @Value("${spring.ai.mcp.server.name:@project.name@}") String serverName,
            @Value("${spring.ai.mcp.server.version:@project.version@}") String serverVersion) {

        // Build server info
        Implementation serverInfo = new Implementation(serverName, serverVersion);

        // Build capabilities
        ServerCapabilities capabilities = ServerCapabilities.builder()
                .tools(Boolean.FALSE)
                .resources(Boolean.FALSE, Boolean.FALSE)
                .prompts(Boolean.FALSE)
                .build();

        // Convert Spring AI @Tool callbacks to MCP SyncToolSpecifications
        List<SyncToolSpecification> toolSpecs = new ArrayList<>();
        for (ToolCallback callback : toolCallbackProvider.getToolCallbacks()) {
            var def = callback.getToolDefinition();
            String inputSchemaStr = def.inputSchema();
            Map<String, Object> inputSchema;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = jsonMapper.readValue(inputSchemaStr, Map.class);
                inputSchema = parsed;
            } catch (IOException e) {
                log.warn(
                        "Failed to parse input schema for tool '{}', using empty schema: {}",
                        def.name(),
                        e.getMessage());
                inputSchema = Map.of("type", "object", "properties", Map.of());
            }

            Tool tool = Tool.builder(def.name())
                    .description(def.description())
                    .inputSchema(inputSchema)
                    .build();
            SyncToolSpecification spec = new SyncToolSpecification(tool, (exchange, callRequest) -> {
                try {
                    String inputJson = jsonMapper.writeValueAsString(callRequest.arguments());
                    String resultJson = callback.call(inputJson);
                    return new CallToolResult(List.of(new TextContent(resultJson)), Boolean.FALSE, null, null);
                } catch (Exception e) {
                    log.error("Tool '{}' execution failed", def.name(), e);
                    return new CallToolResult(
                            List.of(new TextContent("Tool execution error: " + e.getMessage())),
                            Boolean.TRUE,
                            null,
                            null);
                }
            });
            toolSpecs.add(spec);
        }

        log.info("Registered {} MCP tools via stdio transport", toolSpecs.size());

        // Build and return the server
        return McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(toolSpecs)
                .jsonMapper(jsonMapper)
                .validateToolInputs(false)
                .build();
    }
}
