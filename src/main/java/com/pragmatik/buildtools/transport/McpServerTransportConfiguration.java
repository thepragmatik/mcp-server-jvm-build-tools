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
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.ArrayList;
import java.util.List;
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

    private static final int STDIO_INPUT_MAX_CHARS = 1_048_576;

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
        return new PrivacySafeMcpJsonMapper(new JacksonMcpJsonMapper(jsonMapper));
    }

    /**
     * Stdio transport provider — reads JSON-RPC from {@code System.in},
     * writes responses to {@code System.out}.
     * <p>
     * Wrapped in {@link StdioServerTransportDiscoverProvider} so the stdio session also
     * answers the {@code server/discover} JSON-RPC method (2026-07-28 RC, SEP-2575) —
     * the RC's backward-compatibility probe on stdio, for stdio-only deployments where
     * the HTTP discover controllers are inactive. The discover result is built by the
     * shared {@code McpDiscoverController#discoverResult()} source, so the stdio and
     * HTTP payloads cannot drift.
     */
    @Bean
    public McpServerTransportProvider stdioServerTransportProvider(
            JsonMapper jsonMapper, McpDiscoverController discoverController) {
        McpServerTransportProvider stdio = new StdioServerTransportProvider(
                new PrivacySafeMcpJsonMapper(new JacksonMcpJsonMapper(jsonMapper), true),
                System.in,
                System.out,
                STDIO_INPUT_MAX_CHARS);
        return new StdioServerTransportDiscoverProvider(
                stdio,
                // Wrap the framework session factory: sessions created from it answer
                // server/discover ahead of the SDK handler map and delegate everything
                // else to the framework session over the same 1:1 stdio transport.
                frameworkFactory -> sessionTransport -> {
                    McpServerSession frameworkSession = frameworkFactory.create(sessionTransport);
                    return new StdioDiscoverSession(
                            sessionTransport, frameworkSession, discoverController::discoverResult);
                });
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
            NativePromptCatalog nativePrompts,
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
            SyncToolSpecification spec = new SyncToolSpecification(
                    McpToolAdapter.tool(callback, jsonMapper),
                    (exchange, request) -> McpToolAdapter.call(callback, jsonMapper, request));
            toolSpecs.add(spec);
        }

        log.info("Registered {} MCP tools via stdio transport", toolSpecs.size());

        // Build and return the server
        return McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(toolSpecs)
                .prompts(nativePrompts.prompts().stream()
                        .map(prompt -> new io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification(
                                prompt, (exchange, request) -> nativePrompts.getPrompt(request)))
                        .toList())
                .jsonMapper(jsonMapper)
                .validateToolInputs(true)
                .build();
    }
}
