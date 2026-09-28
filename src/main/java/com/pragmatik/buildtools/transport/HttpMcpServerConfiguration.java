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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/** Wires the SDK's stateless Streamable HTTP transport to the guarded tool catalogue. */
@Configuration
@Profile("http")
public class HttpMcpServerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(HttpMcpServerConfiguration.class);

    @Bean
    public JsonMapper httpJsonMapper() {
        return new JsonMapper();
    }

    @Bean
    public McpJsonMapper httpMcpJsonMapper(JsonMapper mapper) {
        return new JacksonMcpJsonMapper(mapper);
    }

    @Bean
    public HttpServletStatelessServerTransport httpMcpTransport(McpJsonMapper mapper) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(mapper)
                .messageEndpoint("/mcp")
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> httpMcpServlet(
            HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, "/mcp");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    public McpStatelessSyncServer httpMcpServer(
            HttpServletStatelessServerTransport transport,
            McpJsonMapper mapper,
            ToolCallbackProvider callbacks,
            @Value("${spring.ai.mcp.server.name:@project.name@}") String name,
            @Value("${spring.ai.mcp.server.version:@project.version@}") String version) {
        List<SyncToolSpecification> specifications = new ArrayList<>();
        for (ToolCallback callback : callbacks.getToolCallbacks()) {
            specifications.add(new SyncToolSpecification(
                    McpToolAdapter.tool(callback, mapper),
                    (context, request) -> McpToolAdapter.call(callback, mapper, request)));
        }
        ServerCapabilities capabilities = ServerCapabilities.builder()
                .tools(Boolean.FALSE)
                .resources(Boolean.FALSE, Boolean.FALSE)
                .prompts(Boolean.FALSE)
                .build();
        log.info("Registered {} MCP tools via HTTP transport", specifications.size());
        return McpServer.sync(transport)
                .serverInfo(new Implementation(name, version))
                .capabilities(capabilities)
                .tools(specifications)
                .jsonMapper(mapper)
                .validateToolInputs(true)
                .build();
    }
}
