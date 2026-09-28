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
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

/** Converts the same guarded callback into both supported MCP transports. */
final class McpToolAdapter {
    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private McpToolAdapter() {}

    static Tool tool(ToolCallback callback, McpJsonMapper mapper) {
        var definition = callback.getToolDefinition();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = mapper.readValue(definition.inputSchema(), Map.class);
            return Tool.builder(definition.name())
                    .description(definition.description())
                    .inputSchema(schema)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Invalid input schema for MCP tool " + definition.name());
        }
    }

    static CallToolResult call(ToolCallback callback, McpJsonMapper mapper, CallToolRequest request) {
        try {
            String input = mapper.writeValueAsString(request.arguments());
            String output = callback.call(input);
            return new CallToolResult(
                    List.of(new TextContent(output)), output.contains("\"isError\":true"), null, null);
        } catch (Exception e) {
            log.warn(
                    "Tool '{}' execution failed; details withheld",
                    callback.getToolDefinition().name());
            return new CallToolResult(
                    List.of(new TextContent("Tool execution failed; details withheld by privacy policy")),
                    Boolean.TRUE,
                    null,
                    null);
        }
    }
}
