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
import java.util.Map;
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
 * consistent with the RC's stateless transport. Server identity, protocol versions,
 * transport profile, and capabilities are all resolved from the shared
 * {@link McpServerIdentity}, so this surface can never drift from the server card or
 * the {@code Mcp-Name} HeaderMismatch check. When the Spring AI MCP server starter is
 * wired in, the framework routes the {@code server/discover} JSON-RPC method through
 * the transport; this controller provides the reachable, dependency-free surface today.
 */
@RestController
@RequestMapping("/mcp/discover")
public class McpDiscoverController {

    private final McpServerIdentity identity;

    public McpDiscoverController(McpServerIdentity identity) {
        this.identity = identity;
    }

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
        serverInfo.put("name", identity.name());
        serverInfo.put("version", identity.version());
        serverInfo.put("vendor", identity.vendor());
        result.put("serverInfo", serverInfo);

        result.put("protocolVersions", identity.protocolVersions());
        // Latest version this server prefers, for clients that want a single value.
        result.put("latestProtocolVersion", identity.latestProtocolVersion());

        // Capabilities advertised as MCP capability objects ({} == supported), from the
        // single shared source so the card and this surface use one identical shape.
        result.put("capabilities", identity.capabilities());

        // Transport characteristics (RC: stateless Streamable HTTP, no sessions/SSE-resumability).
        result.put("transport", identity.transportProfile());

        return result;
    }
}
