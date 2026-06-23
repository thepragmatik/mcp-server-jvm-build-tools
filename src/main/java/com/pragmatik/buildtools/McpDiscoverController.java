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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the {@code server/discover} RPC of the 2026-07-28 RC (SEP-2575).
 * <p>
 * {@code server/discover} lets a client learn a server's supported protocol
 * versions, capabilities, and identity <i>before</i> any other request, so it can
 * select a protocol version up-front instead of relying on the (now removed)
 * {@code initialize} handshake. It also doubles as a backward-compatibility probe.
 * <p>
 * Exposed on the Streamable HTTP transport at {@code /mcp/discover}:
 * <ul>
 *   <li>{@code GET /mcp/discover} — a plain-JSON probe returning the discover result.</li>
 *   <li>{@code POST /mcp/discover} — the JSON-RPC form: send a request with method
 *       {@code "server/discover"}; the same result is returned in a JSON-RPC envelope,
 *       echoing the request {@code id}.</li>
 * </ul>
 * The payload is stateless and identical on every call (no per-connection variance),
 * consistent with the RC's stateless transport. When the Spring AI MCP server starter
 * is wired in, the framework routes the {@code server/discover} JSON-RPC method through
 * the transport; this controller provides the reachable, dependency-free surface today.
 */
@RestController
@RequestMapping("/mcp/discover")
public class McpDiscoverController {

    /** Protocol versions this server can speak (oldest to newest). */
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2024-11-05", "2025-03-26", "2026-07-28");

    @Value("${spring.ai.mcp.server.name:${spring.application.name:mcp-server-jvm-build-tools}}")
    private String serverName = "mcp-server-jvm-build-tools";

    @Value("${spring.ai.mcp.server.version:${buildtools.version:0.1.1-SNAPSHOT}}")
    private String serverVersion = "0.1.1-SNAPSHOT";

    /**
     * Plain-JSON probe form of {@code server/discover}.
     *
     * @return the discover result (server identity, protocol versions, capabilities)
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> discover() {
        return discoverResult();
    }

    /**
     * JSON-RPC form of {@code server/discover}. The request body is optional; when a
     * JSON-RPC {@code id} is supplied it is echoed in the response envelope.
     *
     * @param request the JSON-RPC request body (may be {@code null} for a bare probe)
     * @return a JSON-RPC response envelope wrapping the discover result
     */
    @PostMapping(
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> discoverRpc(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", request == null ? null : request.get("id"));
        envelope.put("result", discoverResult());
        return envelope;
    }

    /**
     * Builds the {@code server/discover} result: server identity, supported protocol
     * versions, advertised capabilities, and transport characteristics. Package-private
     * for direct unit testing.
     *
     * @return an ordered map describing this server
     */
    Map<String, Object> discoverResult() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", serverName);
        serverInfo.put("version", serverVersion);
        serverInfo.put("vendor", "The Pragmatik");
        result.put("serverInfo", serverInfo);

        result.put("protocolVersions", SUPPORTED_PROTOCOL_VERSIONS);
        // Latest version this server prefers, for clients that want a single value.
        result.put("latestProtocolVersion", SUPPORTED_PROTOCOL_VERSIONS.get(SUPPORTED_PROTOCOL_VERSIONS.size() - 1));

        // Capabilities advertised as MCP capability objects ({} == supported).
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        capabilities.put("resources", Map.of("listChanged", false, "subscribe", false));
        capabilities.put("prompts", Map.of("listChanged", false));
        result.put("capabilities", capabilities);

        // Transport characteristics (RC: stateless Streamable HTTP, no sessions/SSE-resumability).
        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("type", "streamable-http");
        transport.put("stateless", true);
        transport.put("sessions", false);
        transport.put("sseResumability", false);
        result.put("transport", transport);

        return result;
    }
}
