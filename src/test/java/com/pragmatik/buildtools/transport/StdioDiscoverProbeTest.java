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

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.application.McpServerIdentity;
import com.pragmatik.buildtools.tool.ToolCatalogueSummary;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests for the stdio {@code server/discover} backward-compatibility probe
 * (2026-07-28 RC, SEP-2575): a full JSON-RPC round trip over the real
 * {@code StdioServerTransportProvider} wiring, proving
 * <ul>
 *   <li>a {@code server/discover} request over stdio yields the result envelope with
 *       {@code serverInfo} and {@code protocolVersions} (issue #178 criterion 1);</li>
 *   <li>the stdio result equals the HTTP {@code /mcp/discover} result, modulo the
 *       transport-specific JSON-RPC envelope (criterion 2);</li>
 *   <li>all other MCP methods still behave as before (no regression).</li>
 * </ul>
 * The test drives the same production wiring as
 * {@code McpServerTransportConfiguration#stdioServerTransportProvider}: the
 * {@code StdioServerTransportProvider} wrapped in
 * {@link StdioServerTransportDiscoverProvider}, with in-memory streams standing in for
 * the real process stdin/stdout (which the transport provider allows via its
 * stream-injection constructor).
 */
@DisplayName("server/discover stdio backward-compatibility probe")
class StdioDiscoverProbeTest {

    private static final String SERVER_NAME = "test-server";

    /** Fake tool service so the wired server registers a real, deterministic catalogue. */
    static class FakeTools {
        @org.springframework.ai.tool.annotation.Tool(name = "alpha_one", description = "fake")
        public String alphaOne() {
            return "ok";
        }
    }

    private final FakeTools tools = new FakeTools();

    /**
     * Builds the shared-source discover controller exactly as the HTTP surfaces use it
     * (identity + deterministic catalogue summary). A fresh instance per call is safe:
     * both are pure functions of the same immutable identity/catalogue inputs.
     */
    private McpDiscoverController newDiscoverController() {
        McpServerIdentity identity = new McpServerIdentity(SERVER_NAME, "9.9.9");
        var provider = new com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider(
                org.springframework.ai.tool.ToolCallbackProvider.from(
                        org.springframework.ai.support.ToolCallbacks.from(tools)));
        ToolCatalogueSummary toolSummary = new ToolCatalogueSummary(provider, List.of(tools));
        return new McpDiscoverController(identity, toolSummary);
    }

    /**
     * Sends one JSON-RPC line to a live stdio server wired with the production
     * discover decorator and returns the (single) JSON-RPC response line written to
     * stdout.
     */
    private String sendAndAwaitResponse(String jsonRequest) throws Exception {
        PipedOutputStream toStdin = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(toStdin);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        McpDiscoverController discoverController = newDiscoverController();

        McpServerTransportProvider stdio = new StdioServerTransportProvider(jsonMapper, stdin, stdout);
        McpServerTransportProvider decorated =
                new StdioServerTransportDiscoverProvider(stdio, frameworkFactory -> sessionTransport -> {
                    McpServerSession frameworkSession = frameworkFactory.create(sessionTransport);
                    return new StdioDiscoverSession(
                            sessionTransport, frameworkSession, discoverController::discoverResult);
                });
        McpSyncServer server = McpServer.sync(decorated)
                .serverInfo(SERVER_NAME, "9.9.9")
                .capabilities(ServerCapabilities.builder().tools(Boolean.FALSE).build())
                .jsonMapper(jsonMapper)
                .build();

        try {
            toStdin.write((jsonRequest + "\n").getBytes(StandardCharsets.UTF_8));
            toStdin.flush();

            // The stdio session transport writes asynchronously; poll for a complete line.
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                String out = stdout.toString(StandardCharsets.UTF_8);
                if (out.indexOf('\n') >= 0) {
                    return out.substring(0, out.indexOf('\n'));
                }
                Thread.sleep(20);
            }
            throw new AssertionError("No JSON-RPC response within 5s for: " + jsonRequest);
        } finally {
            toStdin.close();
            server.close();
        }
    }

    @Test
    @DisplayName("server/discover over stdio yields the result envelope with serverInfo and protocolVersions")
    void stdioProbeReturnsDiscoverEnvelope() throws Exception {
        String response = sendAndAwaitResponse("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"server/discover\"}");

        JsonMapper mapper = new JsonMapper();
        var tree = mapper.readTree(response);
        assertThat(tree.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(tree.get("id").asInt()).isEqualTo(42);
        var result = tree.get("result");
        assertThat(result).isNotNull();
        assertThat(result.get("serverInfo").get("name").asString()).isEqualTo(SERVER_NAME);
        assertThat(result.get("protocolVersions").size()).isEqualTo(3);
        assertThat(result.get("protocolVersions").get(2).asString()).isEqualTo("2026-07-28");
        assertThat(result.get("capabilities")).isNotNull();
    }

    @Test
    @DisplayName("stdio result equals the HTTP /mcp/discover result (single shared source)")
    void stdioResultEqualsHttpDiscoverResult() throws Exception {
        String response = sendAndAwaitResponse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\"}");
        JsonMapper mapper = new JsonMapper();
        var stdioResult = mapper.readTree(response).get("result");

        // The HTTP surface's result, built from the same shared McpServerIdentity source.
        Map<String, Object> httpResult = newDiscoverController().discoverResult();
        var httpTree = mapper.readTree(mapper.writeValueAsString(httpResult));

        assertThat(stdioResult).isEqualTo(httpTree);
    }

    @Test
    @DisplayName("server/discover works before the initialize handshake (up-front probe)")
    void probeWorksWithoutInitialize() throws Exception {
        String response = sendAndAwaitResponse("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"server/discover\"}");
        assertThat(response).contains("\"serverInfo\"");
        assertThat(response).contains("\"id\":\"abc\"");
    }

    @Test
    @DisplayName("unknown method still returns Method not found (no regression)")
    void unknownMethodStillErrors() throws Exception {
        String response = sendAndAwaitResponse("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"no/such/method\"}");
        assertThat(response).contains("\"error\"");
        assertThat(response).contains("-32601");
    }

    @Test
    @DisplayName("initialize handshake still works through the delegate session (no regression)")
    void initializeStillWorksWithoutDiscoverInterference() throws Exception {
        String response = sendAndAwaitResponse("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"client\",\"version\":\"1.0\"}}}");
        assertThat(response).contains("\"result\"");
        assertThat(response).contains("\"id\":9");
        assertThat(response).contains("\"serverInfo\"");
    }
}
