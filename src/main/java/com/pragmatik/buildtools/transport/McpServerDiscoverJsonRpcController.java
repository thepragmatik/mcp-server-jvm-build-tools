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
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the {@code server/discover} JSON-RPC method (2026-07-28 RC, SEP-2575) on the
 * framework's Streamable HTTP protocol endpoint, {@code POST /mcp}.
 * <p>
 * The standalone {@link McpDiscoverController} serves the dependency-free REST/JSON-RPC probe at
 * {@code /mcp/discover}; a protocol-speaking client, however, sends
 * {@code {"method":"server/discover"}} to the actual MCP JSON-RPC endpoint ({@code POST /mcp}),
 * which no framework handler answered before this controller. Both surfaces build their result
 * from the shared {@link McpServerIdentity} via {@link McpDiscoverController#discoverResult()},
 * so the payload cannot drift between the two routes.
 * <p>
 * The bundled Spring AI {@code spring-ai-mcp} server wiring does not natively route
 * {@code server/discover}, so this explicit handler is the fallback the research document
 * ({@code docs/mcp-005-research.md}, Slice 1) prescribes. Only the {@code server/discover}
 * method is answered here: any other JSON-RPC method receives a standard
 * {@code Method not found} error envelope ({@code -32601}) so a method this server does not
 * implement is never mistaken for discover.
 * <p>
 * Discover is a <i>pre-auth</i> surface: {@code OAuthResourceServerFilter} exempts
 * {@code server/discover} bodies on {@code POST /mcp} from bearer enforcement, and this
 * endpoint is reachable with the filter enabled. Requests carrying
 * {@code Mcp-Method}/{@code Mcp-Name} headers still pass through
 * {@link McpHeaderValidationFilter} unchanged: an {@code Mcp-Method: server/discover} header
 * matches this request's JSON-RPC {@code method}, and the filter only rejects a
 * present-and-contradictory header.
 */
@RestController
public class McpServerDiscoverJsonRpcController {

    /** The JSON-RPC method name this endpoint answers (2026-07-28 RC, SEP-2575). */
    static final String METHOD_SERVER_DISCOVER = "server/discover";

    /** JSON-RPC "Method not found" error code. */
    static final int JSONRPC_METHOD_NOT_FOUND = -32601;

    private final McpDiscoverController discoverController;

    public McpServerDiscoverJsonRpcController(McpDiscoverController discoverController) {
        this.discoverController = discoverController;
    }

    /**
     * Handles the {@code server/discover} JSON-RPC method on {@code POST /mcp}, returning the
     * same result object as the {@code /mcp/discover} probe in a JSON-RPC response envelope
     * that echoes the request {@code id}. Any other method is answered with a
     * {@code Method not found} error envelope.
     *
     * @param request the JSON-RPC request body (may be {@code null} for a bare probe)
     * @return a JSON-RPC response envelope wrapping the discover result, or the error envelope
     */
    @PostMapping(
            path = "/mcp",
            consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> serverDiscover(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", request == null ? null : request.get("id"));

        Object method = request == null ? null : request.get("method");
        if (METHOD_SERVER_DISCOVER.equals(method) || method == null) {
            // A body without a method is treated as a bare discover probe, mirroring the
            // null-body tolerance of the /mcp/discover JSON-RPC probe.
            envelope.put("result", discoverController.discoverResult());
        } else {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", JSONRPC_METHOD_NOT_FOUND);
            error.put("message", "Method not found: " + method);
            envelope.put("error", error);
        }
        return envelope;
    }
}
