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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpDiscoverController} — the {@code server/discover} RPC
 * (SEP-2575) exposing protocol versions, capabilities and identity.
 */
@DisplayName("McpDiscoverController")
class McpDiscoverControllerTest {

    private McpDiscoverController controller;

    @BeforeEach
    void setUp() {
        controller = new McpDiscoverController(new McpServerIdentity("test-server", "9.9.9"));
    }

    @Test
    @DisplayName("GET discover advertises identity, protocol versions and capabilities")
    @SuppressWarnings("unchecked")
    void discoverAdvertisesEverything() {
        Map<String, Object> result = controller.discover();

        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertThat(serverInfo).containsEntry("name", "test-server").containsEntry("version", "9.9.9");
        assertThat(serverInfo).containsKey("vendor");

        assertThat((java.util.List<String>) result.get("protocolVersions"))
                .containsExactly("2024-11-05", "2025-03-26", "2026-07-28");
        assertThat(result).containsEntry("latestProtocolVersion", "2026-07-28");

        Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
        assertThat(capabilities).containsKeys("tools", "resources", "prompts");
    }

    @Test
    @DisplayName("discover transport metadata reflects the stateless RC")
    @SuppressWarnings("unchecked")
    void transportIsStateless() {
        Map<String, Object> transport =
                (Map<String, Object>) controller.discover().get("transport");
        assertThat(transport)
                .containsEntry("type", "streamable-http")
                .containsEntry("stateless", true)
                .containsEntry("sessions", false)
                .containsEntry("sseResumability", false);
    }

    @Test
    @DisplayName("POST discover echoes the JSON-RPC id and wraps the result")
    @SuppressWarnings("unchecked")
    void rpcEchoesId() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 17);
        request.put("method", "server/discover");

        Map<String, Object> envelope = controller.discoverRpc(request);

        assertThat(envelope).containsEntry("jsonrpc", "2.0").containsEntry("id", 17);
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertThat(result).containsKey("serverInfo").containsKey("protocolVersions");
    }

    @Test
    @DisplayName("POST discover with no body returns a null id envelope")
    void rpcNullBody() {
        Map<String, Object> envelope = controller.discoverRpc(null);
        assertThat(envelope).containsEntry("jsonrpc", "2.0").containsKey("result");
        assertThat(envelope.get("id")).isNull();
    }

    @Test
    @DisplayName("supported protocol versions include the 2026-07-28 RC")
    void supportedVersionsConstant() {
        assertThat(McpServerIdentity.SUPPORTED_PROTOCOL_VERSIONS).contains("2026-07-28");
    }
}
