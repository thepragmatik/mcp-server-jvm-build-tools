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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-surface consistency suite for the {@code server/discover} result (mcp-005 slice 4,
 * issue #179): the same discover payload is served over <b>three</b> delivery surfaces —
 *
 * <ul>
 *   <li>{@code POST /mcp} — the MCP JSON-RPC protocol endpoint;</li>
 *   <li>{@code GET /mcp/discover} — the plain-JSON HTTP probe;</li>
 *   <li>stdio — the JSON-RPC backward-compatibility probe written through the stdio session
 *       transport ({@link StdioDiscoverSession}).
 * </ul>
 *
 * <p>All three must return a <b>deep-equal</b> discover result, modulo only the transport-specific
 * JSON-RPC envelope ({@code jsonrpc}/{@code id} wrapper on the two RPC surfaces). The suite also
 * asserts the shared identity fields agree between the server card ({@code /.well-known/mcp-server})
 * and the discover result, and that the {@code buildtools.discover.tools-summary} config knob
 * propagates identically ({@code count}, {@code full}, {@code none}) to every surface.
 *
 * <p><b>Documented, intentional surface asymmetries</b> (not drift, and asserted only where the
 * asymmetry is defined):
 *
 * <ul>
 *   <li>the two HTTP RPC surfaces wrap the result in a JSON-RPC envelope; {@code GET
 *       /mcp/discover} returns the bare result object;</li>
 *   <li>the server card layers card-only metadata on top of the shared capabilities ({@code
 *       logging}, {@code extensions}) and does not carry the {@code tools} summary — those keys
 *       are card-specific and excluded from the shared-field comparison;</li>
 *   <li>the card names the versions list {@code mcpVersions} where discover uses
 *       {@code protocolVersions} — same list, different key.</li>
 * </ul>
 */
@DisplayName("server/discover cross-surface consistency (issue #179)")
class DiscoverCrossSurfaceConsistencyTest {

    private static final String SERVER_NAME = "test-server";

    private static final String VERSION = "9.9.9";

    private static final String DISCOVER_BODY = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\"}";

    /** Fake tool service driving the tools summary (service group: fakeConsistencyTools). */
    static class FakeConsistencyTools {
        @org.springframework.ai.tool.annotation.Tool(name = "consistency_alpha", description = "fake")
        public String consistencyAlpha() {
            return "ok";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "consistency_beta", description = "fake")
        public String consistencyBeta() {
            return "ok";
        }
    }

    private final JsonMapper mapper = new JsonMapper();

    /** The shared identity all surfaces under test read from. */
    private final McpServerIdentity identity = new McpServerIdentity(SERVER_NAME, VERSION);

    /**
     * Builds the discover controller exactly as the HTTP surfaces use it, with the given tools
     * summary level. A fresh instance per call is safe: both inputs are pure functions of the
     * same immutable identity/catalogue configuration.
     */
    private McpDiscoverController newDiscoverController(ToolCatalogueSummary.Mode mode) {
        Object tools = new FakeConsistencyTools();
        var provider = new com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider(
                org.springframework.ai.tool.ToolCallbackProvider.from(
                        org.springframework.ai.support.ToolCallbacks.from(tools)));
        ToolCatalogueSummary toolSummary = new ToolCatalogueSummary(provider, List.of(tools), mode);
        return new McpDiscoverController(identity, toolSummary);
    }

    /** Standalone MockMvc wiring both HTTP discover surfaces exactly as production routes them. */
    private MockMvc newHttpSurface(ToolCatalogueSummary.Mode mode) {
        McpDiscoverController discoverController = newDiscoverController(mode);
        return MockMvcBuilders.standaloneSetup(
                        new McpServerDiscoverJsonRpcController(discoverController),
                        discoverController,
                        new ServerCardController(identity))
                .build();
    }

    // ── Surface clients ─────────────────────────────────────────────────────────

    /** GET /mcp/discover — the plain-JSON probe. Returns the bare result as a JSON tree. */
    private JsonNode discoverViaHttpGet(MockMvc http) throws Exception {
        MvcResult mvc =
                http.perform(get("/mcp/discover")).andExpect(status().isOk()).andReturn();
        return mapper.readTree(mvc.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** POST /mcp with the server/discover JSON-RPC method. Returns the {@code result} node. */
    private JsonNode discoverViaPostMcp(MockMvc http) throws Exception {
        MvcResult mvc = http.perform(
                        post("/mcp").contentType(MediaType.APPLICATION_JSON).content(DISCOVER_BODY))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode envelope = mapper.readTree(mvc.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(envelope.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(envelope.get("id").asInt()).isEqualTo(7);
        return envelope.get("result");
    }

    /**
     * Sends one {@code server/discover} JSON-RPC line to a live stdio server wired with the
     * production discover decorator and returns the {@code result} node of the response line.
     * Drives the same production wiring as {@code McpServerTransportConfiguration} and
     * {@code StdioDiscoverProbeTest}: in-memory streams stand in for stdin/stdout.
     */
    private JsonNode discoverViaStdio(ToolCatalogueSummary.Mode mode) throws Exception {
        PipedOutputStream toStdin = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(toStdin);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());
        McpDiscoverController discoverController = newDiscoverController(mode);

        McpServerTransportProvider stdio = new StdioServerTransportProvider(jsonMapper, stdin, stdout);
        McpServerTransportProvider decorated =
                new StdioServerTransportDiscoverProvider(stdio, frameworkFactory -> sessionTransport -> {
                    McpServerSession frameworkSession = frameworkFactory.create(sessionTransport);
                    return new StdioDiscoverSession(
                            sessionTransport, frameworkSession, discoverController::discoverResult);
                });
        McpSyncServer server = McpServer.sync(decorated)
                .serverInfo(SERVER_NAME, VERSION)
                .capabilities(ServerCapabilities.builder().tools(Boolean.FALSE).build())
                .jsonMapper(jsonMapper)
                .build();

        try {
            toStdin.write(("{" + "\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"server/discover\"" + "}\n")
                    .getBytes(StandardCharsets.UTF_8));
            toStdin.flush();

            // The stdio session transport writes asynchronously; poll for a complete line.
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                String out = stdout.toString(StandardCharsets.UTF_8);
                if (out.indexOf('\n') >= 0) {
                    String line = out.substring(0, out.indexOf('\n'));
                    JsonNode envelope = mapper.readTree(line);
                    assertThat(envelope.get("jsonrpc").asString()).isEqualTo("2.0");
                    assertThat(envelope.get("id").asInt()).isEqualTo(42);
                    assertThat(envelope.get("error")).as("no error envelope").isNull();
                    return envelope.get("result");
                }
                Thread.sleep(20);
            }
            throw new AssertionError("No JSON-RPC response within 5s over stdio");
        } finally {
            toStdin.close();
            server.close();
        }
    }

    // ── The consistency assertions ──────────────────────────────────────────────

    @Test
    @DisplayName("discover result is deep-equal across POST /mcp, GET /mcp/discover and stdio")
    void discoverResultDeepEqualAcrossAllThreeSurfaces() throws Exception {
        MockMvc http = newHttpSurface(ToolCatalogueSummary.Mode.FULL);

        JsonNode viaPostMcp = discoverViaPostMcp(http);
        JsonNode viaHttpGet = discoverViaHttpGet(http);
        JsonNode viaStdio = discoverViaStdio(ToolCatalogueSummary.Mode.FULL);

        assertThat(viaPostMcp).as("POST /mcp vs GET /mcp/discover").isEqualTo(viaHttpGet);
        assertThat(viaPostMcp).as("POST /mcp vs stdio").isEqualTo(viaStdio);
        assertThat(viaHttpGet).as("GET /mcp/discover vs stdio").isEqualTo(viaStdio);

        // Shared core shape is present on every surface.
        for (String key : List.of(
                "serverInfo",
                "protocolVersions",
                "latestProtocolVersion",
                "capabilities",
                "cacheHints",
                "transport",
                "tools")) {
            assertThat(viaHttpGet.has(key))
                    .as("key '%s' present on every surface", key)
                    .isTrue();
            assertThat(viaStdio.has(key))
                    .as("key '%s' present on every surface", key)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("server card and discover result agree on every shared identity field")
    @SuppressWarnings("unchecked")
    void serverCardAgreesWithDiscoverResult() {
        MockMvc http;
        try {
            http = newHttpSurface(ToolCatalogueSummary.Mode.FULL);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        McpDiscoverController controller = newDiscoverController(ToolCatalogueSummary.Mode.FULL);
        Map<String, Object> discover;
        Map<String, Object> card;
        try {
            discover = controller.discover();
            card = (Map<String, Object>) mapper.readValue(
                    http.perform(get("/.well-known/mcp-server"))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(StandardCharsets.UTF_8),
                    Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Map<String, Object> serverInfo = (Map<String, Object>) discover.get("serverInfo");

        // name / version / vendor — identical literal values.
        assertThat(card.get("name")).as("card name").isEqualTo(serverInfo.get("name"));
        assertThat(card.get("version")).as("card version").isEqualTo(serverInfo.get("version"));
        assertThat(card.get("vendor")).as("card vendor").isEqualTo(serverInfo.get("vendor"));

        // mcpVersions (card) == protocolVersions (discover) — same list, documented key rename.
        assertThat(card.get("mcpVersions")).as("card mcpVersions").isEqualTo(discover.get("protocolVersions"));

        // capabilities — the card layers card-only metadata (logging/extensions) on top of the
        // shared map; every shared entry must be identical, and the shared map must be a prefix
        // (card superset) of what discover advertises.
        Map<String, Object> discoverCaps = (Map<String, Object>) discover.get("capabilities");
        Map<String, Object> cardCaps = (Map<String, Object>) card.get("capabilities");
        for (String key : discoverCaps.keySet()) {
            assertThat(mapper.valueToTree(cardCaps.get(key)).equals(mapper.valueToTree(discoverCaps.get(key))))
                    .as("card capability '%s' agrees with discover", key)
                    .isTrue();
        }
        assertThat(cardCaps)
                .as("card layers card-only metadata on shared capabilities")
                .containsKeys("logging", "extensions");

        // cacheHints — identical on both surfaces (same shared source object). Both are compared
        // via their canonical JSON form so numeric deserialisation differences (Integer vs Long
        // from Map deserialisation vs raw identity values) cannot produce false drift.
        assertThat(mapper.writeValueAsString(card.get("cacheHints")))
                .as("card cacheHints")
                .isEqualTo(mapper.writeValueAsString(discover.get("cacheHints")));

        // transport profile — identical on both surfaces.
        assertThat(mapper.writeValueAsString(card.get("transportProfile")))
                .as("card transportProfile")
                .isEqualTo(mapper.writeValueAsString(discover.get("transport")));

        // discover pointer: the card advertises the probe route that serves the result.
        assertThat(card.get("discover")).as("card discover pointer").isEqualTo("/mcp/discover");

        // Shared fields must trace back to the single McpServerIdentity source.
        assertThat(card.get("mcpVersions")).isEqualTo(McpServerIdentity.SUPPORTED_PROTOCOL_VERSIONS);
        assertThat(serverInfo.get("name")).isEqualTo(identity.name());
        assertThat(serverInfo.get("version")).isEqualTo(identity.version());
    }

    @Test
    @DisplayName("tools-summary=count propagates identically to all three surfaces")
    void toolsSummaryCountPropagatesToAllSurfaces() throws Exception {
        ToolCatalogueSummary.Mode mode = ToolCatalogueSummary.Mode.COUNT;
        MockMvc http = newHttpSurface(mode);

        JsonNode viaPostMcp = discoverViaPostMcp(http);
        JsonNode viaHttpGet = discoverViaHttpGet(http);
        JsonNode viaStdio = discoverViaStdio(mode);

        for (JsonNode result : List.of(viaPostMcp, viaHttpGet, viaStdio)) {
            JsonNode tools = result.get("tools");
            assertThat(tools).as("tools summary present in count mode").isNotNull();
            assertThat(fieldNames(tools))
                    .as("count mode advertises only the count")
                    .containsExactly("count");
            assertThat(tools.get("count").asInt()).isEqualTo(2);
        }
        assertThat(viaPostMcp.get("tools")).isEqualTo(viaHttpGet.get("tools"));
        assertThat(viaPostMcp.get("tools")).isEqualTo(viaStdio.get("tools"));
    }

    @Test
    @DisplayName("tools-summary=none omits the tools key on all three surfaces (legacy payload)")
    void toolsSummaryNoneOmitsToolsKeyOnAllSurfaces() throws Exception {
        ToolCatalogueSummary.Mode mode = ToolCatalogueSummary.Mode.NONE;
        MockMvc http = newHttpSurface(mode);

        JsonNode viaPostMcp = discoverViaPostMcp(http);
        JsonNode viaHttpGet = discoverViaHttpGet(http);
        JsonNode viaStdio = discoverViaStdio(mode);

        for (JsonNode result : List.of(viaPostMcp, viaHttpGet, viaStdio)) {
            assertThat(result.has("tools"))
                    .as("tools-summary=none emits no 'tools' key")
                    .isFalse();
            // The rest of the legacy payload is untouched.
            for (String key : List.of(
                    "serverInfo",
                    "protocolVersions",
                    "latestProtocolVersion",
                    "capabilities",
                    "cacheHints",
                    "transport")) {
                assertThat(result.has(key)).as("legacy key '%s' untouched", key).isTrue();
            }
        }
        // And the three bare results remain deep-equal with the summary shed.
        assertThat(viaPostMcp).isEqualTo(viaHttpGet);
        assertThat(viaPostMcp).isEqualTo(viaStdio);
    }

    /** Ordered field names of an object node, as a list of strings. */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new java.util.ArrayList<>();
        names.addAll(object.propertyNames());
        return names;
    }

    /** String values of an array node, as a list of strings. */
    private static List<String> namesOf(JsonNode array) {
        List<String> names = new java.util.ArrayList<>();
        array.forEach(element -> names.add(element.asString()));
        return names;
    }

    @Test
    @DisplayName("config knob default (full) advertises the full summary shape on all surfaces")
    void toolsSummaryFullShapeIdenticalOnAllSurfaces() throws Exception {
        ToolCatalogueSummary.Mode mode = ToolCatalogueSummary.Mode.FULL;
        MockMvc http = newHttpSurface(mode);

        JsonNode viaPostMcp = discoverViaPostMcp(http);
        JsonNode viaHttpGet = discoverViaHttpGet(http);
        JsonNode viaStdio = discoverViaStdio(mode);

        for (JsonNode result : List.of(viaPostMcp, viaHttpGet, viaStdio)) {
            JsonNode tools = result.get("tools");
            assertThat(fieldNames(tools))
                    .as("full mode advertises count/names/groups")
                    .containsExactly("count", "names", "groups");
            assertThat(tools.get("count").asInt()).isEqualTo(2);
            assertThat(namesOf(tools.get("names"))).containsExactly("consistency_alpha", "consistency_beta");
            assertThat(tools.get("groups").get("fakeConsistencyTools")).hasSize(2);
        }
    }
}
