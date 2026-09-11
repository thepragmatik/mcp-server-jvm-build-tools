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
import java.util.LinkedHashMap;
import java.util.List;
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
        // Two fake service beans whose @Tool methods give deterministic service grouping.
        Object toolsA = new FakeToolsA();
        Object toolsB = new FakeToolsB();
        var provider = new com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider(
                org.springframework.ai.tool.ToolCallbackProvider.from(
                        org.springframework.ai.support.ToolCallbacks.from(toolsA, toolsB)));
        ToolCatalogueSummary summary = new ToolCatalogueSummary(provider, List.of(toolsA, toolsB));
        controller = new McpDiscoverController(new McpServerIdentity("test-server", "9.9.9"), summary);
    }

    /** Fake tool service: two tools in the same group. */
    static class FakeToolsA {
        @org.springframework.ai.tool.annotation.Tool(name = "alpha_two", description = "fake")
        public String alphaTwo() {
            return "ok";
        }

        @org.springframework.ai.tool.annotation.Tool(name = "alpha_one", description = "fake")
        public String alphaOne() {
            return "ok";
        }
    }

    /** Fake tool service in a different group. */
    static class FakeToolsB {
        @org.springframework.ai.tool.annotation.Tool(name = "beta_tool", description = "fake")
        public String betaTool() {
            return "ok";
        }
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

    @Test
    @DisplayName("discover advertises per-method cache hints (ttlMs/cacheScope, SEP-2549)")
    @SuppressWarnings("unchecked")
    void discoverAdvertisesCacheHints() {
        Map<String, Object> hints = (Map<String, Object>) controller.discover().get("cacheHints");

        assertThat(hints)
                .containsKeys(
                        "tools/list", "prompts/list", "resources/list", "resources/templates/list", "resources/read");
        Map<String, Object> toolsList = (Map<String, Object>) hints.get("tools/list");
        assertThat(toolsList).containsKey("ttlMs").containsEntry("cacheScope", "public");
        Map<String, Object> read = (Map<String, Object>) hints.get("resources/read");
        assertThat(read).containsKey("ttlMs").containsEntry("cacheScope", "private");
    }

    @Test
    @DisplayName("discover advertises the additive tools summary (full, default)")
    @SuppressWarnings("unchecked")
    void discoverAdvertisesToolsSummary() {
        Map<String, Object> result = controller.discover();
        Map<String, Object> tools = (Map<String, Object>) result.get("tools");

        assertThat(tools).isNotNull();
        assertThat((Integer) tools.get("count")).isEqualTo(3);
        assertThat((List<String>) tools.get("names")).containsExactly("alpha_one", "alpha_two", "beta_tool");

        Map<String, List<String>> groups = (Map<String, List<String>>) tools.get("groups");
        assertThat(groups).containsOnlyKeys("fakeToolsA", "fakeToolsB");
        assertThat(groups.get("fakeToolsA")).containsExactly("alpha_one", "alpha_two");
        assertThat(groups.get("fakeToolsB")).containsExactly("beta_tool");
    }

    @Test
    @DisplayName("tools summary is deterministic: names sorted and identical across repeated calls")
    @SuppressWarnings("unchecked")
    void toolsSummaryIsDeterministic() {
        Map<String, Object> first = controller.discover();
        Map<String, Object> second = controller.discover();

        assertThat(first).isEqualTo(second);
        List<String> names = (List<String>) ((Map<String, Object>) first.get("tools")).get("names");
        assertThat(names).isSorted().containsExactlyInAnyOrderElementsOf(names);
    }

    @Test
    @DisplayName("groups are keyed by service: every tool appears in exactly one group")
    @SuppressWarnings("unchecked")
    void groupsKeyedByService() {
        Map<String, Object> tools = (Map<String, Object>) controller.discover().get("tools");
        Map<String, List<String>> groups = (Map<String, List<String>>) tools.get("groups");

        int grouped = groups.values().stream().mapToInt(List::size).sum();
        assertThat(grouped).as("every tool is grouped").isEqualTo((Integer) tools.get("count"));
        assertThat(groups.keySet()).doesNotContain(ToolCatalogueSummary.UNGROUPED);
    }

    @Test
    @DisplayName("tools-summary=count advertises only the count")
    @SuppressWarnings("unchecked")
    void countModeAdvertisesOnlyCount() {
        Map<String, Object> result =
                controllerWithMode(ToolCatalogueSummary.Mode.COUNT).discover();
        Map<String, Object> tools = (Map<String, Object>) result.get("tools");

        assertThat(tools).containsOnlyKeys("count");
        assertThat((Integer) tools.get("count")).isEqualTo(3);
    }

    @Test
    @DisplayName("tools-summary=none reproduces the exact legacy payload (no tools key)")
    void noneModeOmitsToolsKey() {
        Map<String, Object> result =
                controllerWithMode(ToolCatalogueSummary.Mode.NONE).discover();

        assertThat(result).doesNotContainKey("tools");
        // Everything else is untouched relative to the legacy shape.
        assertThat(result)
                .containsKeys(
                        "serverInfo",
                        "protocolVersions",
                        "latestProtocolVersion",
                        "capabilities",
                        "cacheHints",
                        "transport");
    }

    @Test
    @DisplayName("unregistered tool names fall back to the 'ungrouped' key")
    @SuppressWarnings("unchecked")
    void unregisteredNamesFallBackToUngrouped() {
        Object toolsA = new FakeToolsA();
        // Provider carries beta_tool, but only FakeToolsA is registered for grouping.
        var provider = new com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider(
                org.springframework.ai.tool.ToolCallbackProvider.from(
                        org.springframework.ai.support.ToolCallbacks.from(toolsA, new FakeToolsB())));
        ToolCatalogueSummary summary = new ToolCatalogueSummary(provider, List.of(toolsA));
        McpDiscoverController ungrouped =
                new McpDiscoverController(new McpServerIdentity("test-server", "9.9.9"), summary);

        Map<String, List<String>> groups =
                (Map<String, List<String>>) ((Map<String, Object>) ungroupedResult(ungrouped)).get("groups");
        assertThat(groups).containsKey(ToolCatalogueSummary.UNGROUPED);
        assertThat(groups.get(ToolCatalogueSummary.UNGROUPED)).containsExactly("beta_tool");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ungroupedResult(McpDiscoverController ungrouped) {
        Object tools = ungrouped.discover().get("tools");
        return tools instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private McpDiscoverController controllerWithMode(ToolCatalogueSummary.Mode mode) {
        Object toolsA = new FakeToolsA();
        Object toolsB = new FakeToolsB();
        var provider = new com.pragmatik.buildtools.tool.DeterministicToolCallbackProvider(
                org.springframework.ai.tool.ToolCallbackProvider.from(
                        org.springframework.ai.support.ToolCallbacks.from(toolsA, toolsB)));
        ToolCatalogueSummary summary = new ToolCatalogueSummary(provider, List.of(toolsA, toolsB), mode);
        return new McpDiscoverController(new McpServerIdentity("test-server", "9.9.9"), summary);
    }

    @Test
    @DisplayName("no secrets in the summary: values are names and group keys only")
    @SuppressWarnings("unchecked")
    void summaryCarriesNoSecrets() {
        Map<String, Object> tools = (Map<String, Object>) controller.discover().get("tools");

        assertThat(tools.keySet()).containsExactly("count", "names", "groups");
        assertThat((List<String>) tools.get("names"))
                .allSatisfy(name -> assertThat(name).doesNotContain("key", "token", "secret"));
        assertThat(((Map<String, List<String>>) tools.get("groups")).keySet())
                .allSatisfy(group -> assertThat(group).doesNotContain("key", "token", "secret"));
    }
}
