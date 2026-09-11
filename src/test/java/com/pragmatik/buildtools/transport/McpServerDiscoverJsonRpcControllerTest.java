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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmatik.buildtools.application.McpServerIdentity;
import com.pragmatik.buildtools.security.OAuthResourceServerConfig;
import com.pragmatik.buildtools.security.OAuthResourceServerFilter;
import com.pragmatik.buildtools.security.ToolAuthorizationService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that the {@code server/discover} JSON-RPC method (2026-07-28 RC, SEP-2575) is answered
 * by {@code POST /mcp}, following the explicit-configuration pattern of {@code TransportConfigTest}:
 * the MVC stack under test is built with standalone MockMvc around the discover controllers plus
 * the real filter chain, so routing, the OAuth resource-server exemption, and the
 * {@code McpHeaderValidationFilter} HeaderMismatch behaviour are all exercised end-to-end without
 * a full application context.
 */
@DisplayName("POST /mcp — server/discover JSON-RPC routing")
class McpServerDiscoverJsonRpcControllerTest {

    private static final String SERVER_NAME = "MCP Server - Build Tools for the JVM";

    private static final String DISCOVER_BODY = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/discover\"}";

    private McpServerIdentity identity;
    private McpDiscoverController discoverController;
    private McpServerDiscoverJsonRpcController jsonRpcController;
    private McpHeaderValidationFilter headerFilter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        identity = new McpServerIdentity(SERVER_NAME, "9.9.9");
        discoverController = new McpDiscoverController(identity);
        jsonRpcController = new McpServerDiscoverJsonRpcController(discoverController);
        headerFilter =
                new McpHeaderValidationFilter(identity, McpHeaderValidationFilter.DEFAULT_MAX_VALIDATION_BODY_BYTES);
        mockMvc = MockMvcBuilders.standaloneSetup(jsonRpcController, discoverController)
                .addFilters(headerFilter)
                .build();
    }

    @Nested
    @DisplayName("routing through POST /mcp")
    class Routing {

        @Test
        @DisplayName("POST /mcp with server/discover returns the JSON-RPC result envelope")
        void answersServerDiscoverWithJsonRpcEnvelope() throws Exception {
            mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(DISCOVER_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                    .andExpect(jsonPath("$.id").value(7))
                    .andExpect(jsonPath("$.result").exists())
                    .andExpect(jsonPath("$.result.serverInfo.name").value(SERVER_NAME))
                    .andExpect(jsonPath("$.result.protocolVersions.length()").value(3))
                    .andExpect(jsonPath("$.result.protocolVersions[2]").value("2026-07-28"))
                    .andExpect(jsonPath("$.result.capabilities").exists());
        }

        @Test
        @DisplayName("result is identical to the /mcp/discover probe payload (no drift)")
        void resultMatchesDiscoverControllerPayload() throws Exception {
            MvcResult mcpResult = mockMvc.perform(
                            post("/mcp").contentType(MediaType.APPLICATION_JSON).content(DISCOVER_BODY))
                    .andReturn();
            MvcResult probeResult = mockMvc.perform(post("/mcp/discover")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(DISCOVER_BODY))
                    .andReturn();

            ObjectMapper mapper = new JsonMapper();
            var viaMcp = mapper.readTree(mcpResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .get("result");
            var viaProbe = mapper.readTree(probeResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .get("result");
            assertThat(viaProbe).isNotNull();
            assertThat(viaMcp).isEqualTo(viaProbe);
        }

        @Test
        @DisplayName("a body without a method is treated as a bare discover probe")
        void bodyWithoutMethodIsBareProbe() throws Exception {
            mockMvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"id\":3}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(3))
                    .andExpect(jsonPath("$.result.serverInfo.name").value(SERVER_NAME));
        }

        @Test
        @DisplayName("a different JSON-RPC method is NOT answered with a discover result")
        void doesNotShadowOtherMethods() throws Exception {
            mockMvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.error.code").value(-32601))
                    .andExpect(jsonPath("$.result").doesNotExist());
        }
    }

    @Nested
    @DisplayName("OAuth resource-server filter enabled (pre-auth surface)")
    class OAuthExemption {

        @Test
        @DisplayName("server/discover on POST /mcp passes bearer enforcement with no token")
        void discoverIsReachableWithOAuthFilterEnabled() throws Exception {
            OAuthResourceServerFilter oauthFilter = new OAuthResourceServerFilter(
                    new OAuthResourceServerConfig(true, "", List.of()), new ToolAuthorizationService());

            // Filter chain as in the runtime: OAuth (order 1) ahead of header validation (order 2).
            MockMvc chained = MockMvcBuilders.standaloneSetup(jsonRpcController)
                    .addFilters(oauthFilter, headerFilter)
                    .build();

            chained.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(DISCOVER_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.serverInfo.name").value(SERVER_NAME))
                    .andExpect(jsonPath("$.result.protocolVersions[2]").value("2026-07-28"));
        }

        @Test
        @DisplayName("a non-discover method on POST /mcp is still challenged with no token")
        void nonDiscoverStillChallenged() throws Exception {
            OAuthResourceServerFilter oauthFilter = new OAuthResourceServerFilter(
                    new OAuthResourceServerConfig(true, "", List.of()), new ToolAuthorizationService());

            MockMvc chained = MockMvcBuilders.standaloneSetup(jsonRpcController)
                    .addFilters(oauthFilter)
                    .build();

            chained.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }
    }

    @Nested
    @DisplayName("McpHeaderValidationFilter HeaderMismatch behaviour unchanged")
    class HeaderValidation {

        @Test
        @DisplayName("Mcp-Method: server/discover matching the body passes through")
        void matchingMcpMethodHeaderPasses() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
            req.setServletPath("/mcp");
            req.setContentType(MediaType.APPLICATION_JSON_VALUE);
            req.addHeader("Mcp-Method", "server/discover");
            req.setContent(DISCOVER_BODY.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            headerFilter.doFilter(req, res, chain);

            assertThat(res.getStatus()).as("no HeaderMismatch rejection").isEqualTo(200);
            assertThat(chain.getRequest()).as("downstream reached").isNotNull();
        }

        @Test
        @DisplayName("contradictory Mcp-Method header still rejected with HeaderMismatchError")
        void contradictoryMcpMethodHeaderStillRejected() throws Exception {
            String contradictory = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}";
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
            req.setServletPath("/mcp");
            req.setContentType(MediaType.APPLICATION_JSON_VALUE);
            req.addHeader("Mcp-Method", "server/discover");
            req.setContent(contradictory.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            headerFilter.doFilter(req, res, chain);

            assertThat(res.getStatus()).as("HeaderMismatch behaviour unchanged").isEqualTo(400);
            assertThat(res.getContentAsString(StandardCharsets.UTF_8))
                    .contains("HeaderMismatchError")
                    .contains("does not match JSON-RPC body method");
        }

        @Test
        @DisplayName("Mcp-Name mismatch is still rejected for server/discover requests")
        void mcpNameMismatchStillRejected() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp");
            req.setServletPath("/mcp");
            req.setContentType(MediaType.APPLICATION_JSON_VALUE);
            req.addHeader("Mcp-Name", "some-other-server");
            req.setContent(DISCOVER_BODY.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            headerFilter.doFilter(req, res, chain);

            assertThat(res.getStatus()).as("HeaderMismatch behaviour unchanged").isEqualTo(400);
            assertThat(res.getContentAsString(StandardCharsets.UTF_8))
                    .contains("HeaderMismatchError")
                    .contains("Mcp-Name");
        }
    }
}
