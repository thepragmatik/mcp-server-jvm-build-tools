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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ServerCardController} — focuses on the additive MCP RC cache-hint metadata
 * (SEP-2549) advertised on the {@code /.well-known/mcp-server} card.
 */
@DisplayName("ServerCardController")
class ServerCardControllerTest {

    private final ServerCardController controller =
            new ServerCardController(new McpServerIdentity("test-server", "9.9.9"));

    @Test
    @DisplayName("server card advertises per-method cache hints from the shared identity")
    @SuppressWarnings("unchecked")
    void cardAdvertisesCacheHints() {
        Map<String, Object> card = controller.serverCard();

        assertThat(card).containsKey("cacheHints");
        Map<String, Object> hints = (Map<String, Object>) card.get("cacheHints");
        assertThat(hints)
                .containsOnlyKeys(
                        "tools/list", "prompts/list", "resources/list", "resources/templates/list", "resources/read");

        Map<String, Object> toolsList = (Map<String, Object>) hints.get("tools/list");
        assertThat(toolsList)
                .containsEntry("ttlMs", McpServerIdentity.DEFAULT_CATALOG_TTL_MS)
                .containsEntry("cacheScope", "public");
    }

    @Test
    @DisplayName("server card still advertises identity, capabilities and transports (no regression)")
    @SuppressWarnings("unchecked")
    void cardRetainsCoreMetadata() {
        Map<String, Object> card = controller.serverCard();

        assertThat(card).containsEntry("name", "test-server").containsEntry("version", "9.9.9");
        assertThat((java.util.List<String>) card.get("transports")).contains("stdio", "streamable-http");
        assertThat((Map<String, Object>) card.get("capabilities")).containsKeys("tools", "resources", "prompts");
    }
}
