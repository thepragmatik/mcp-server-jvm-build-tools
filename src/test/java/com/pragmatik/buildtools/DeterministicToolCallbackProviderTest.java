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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Unit tests for {@link DeterministicToolCallbackProvider} — verifies that {@code tools/list}
 * ordering is stabilised by sorting on tool name (MCP RC, SEP-2549).
 */
@DisplayName("DeterministicToolCallbackProvider")
class DeterministicToolCallbackProviderTest {

    /** A minimal {@link ToolCallback} whose only meaningful property is its tool name. */
    private static ToolCallback tool(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test tool " + name)
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return "";
            }
        };
    }

    private static ToolCallbackProvider providerOf(ToolCallback... tools) {
        return () -> tools.clone();
    }

    private static String[] namesOf(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).toArray(String[]::new);
    }

    @Test
    @DisplayName("returns tools sorted by name regardless of delegate order")
    void sortsByName() {
        ToolCallbackProvider delegate = providerOf(tool("run_build"), tool("analyze_output"), tool("check_deps"));

        ToolCallback[] result = new DeterministicToolCallbackProvider(delegate).getToolCallbacks();

        assertThat(namesOf(result)).containsExactly("analyze_output", "check_deps", "run_build");
    }

    @Test
    @DisplayName("produces a stable order across repeated calls and across different delegate orderings")
    void stableAcrossCallsAndInputOrderings() {
        ToolCallback a = tool("alpha");
        ToolCallback b = tool("bravo");
        ToolCallback c = tool("charlie");

        String[] order1 = namesOf(new DeterministicToolCallbackProvider(providerOf(c, a, b)).getToolCallbacks());
        String[] order2 = namesOf(new DeterministicToolCallbackProvider(providerOf(b, c, a)).getToolCallbacks());

        DeterministicToolCallbackProvider provider = new DeterministicToolCallbackProvider(providerOf(b, a, c));
        String[] firstCall = namesOf(provider.getToolCallbacks());
        String[] secondCall = namesOf(provider.getToolCallbacks());

        assertThat(order1).containsExactly("alpha", "bravo", "charlie");
        assertThat(order2).isEqualTo(order1);
        assertThat(firstCall).isEqualTo(order1);
        assertThat(secondCall).isEqualTo(firstCall);
    }

    @Test
    @DisplayName("returns a fresh array each call so the delegate's array cannot be mutated")
    void returnsFreshArray() {
        ToolCallback[] backing = {tool("zeta"), tool("alpha")};
        ToolCallbackProvider delegate = () -> backing;
        DeterministicToolCallbackProvider provider = new DeterministicToolCallbackProvider(delegate);

        ToolCallback[] sorted = provider.getToolCallbacks();
        sorted[0] = null; // mutate the returned array

        // The delegate's backing array is untouched and a later call is still correctly sorted.
        assertThat(backing[0].getToolDefinition().name()).isEqualTo("zeta");
        assertThat(namesOf(provider.getToolCallbacks())).containsExactly("alpha", "zeta");
    }

    @Test
    @DisplayName("handles an empty catalogue")
    void handlesEmpty() {
        assertThat(new DeterministicToolCallbackProvider(providerOf()).getToolCallbacks())
                .isEmpty();
    }

    @Test
    @DisplayName("treats a null delegate array as empty")
    void handlesNullDelegateArray() {
        ToolCallbackProvider delegate = () -> null;
        assertThat(new DeterministicToolCallbackProvider(delegate).getToolCallbacks())
                .isEmpty();
    }

    @Test
    @DisplayName("rejects a null delegate")
    void rejectsNullDelegate() {
        assertThatThrownBy(() -> new DeterministicToolCallbackProvider(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
