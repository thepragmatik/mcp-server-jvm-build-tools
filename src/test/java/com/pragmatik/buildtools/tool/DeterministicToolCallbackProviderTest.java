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
package com.pragmatik.buildtools.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Unit tests for {@link DeterministicToolCallbackProvider} — proves the decorator returns the
 * wrapped provider's tools in a stable, name-sorted order (MCP RC, SEP-2549) without mutating the
 * delegate.
 */
@DisplayName("DeterministicToolCallbackProvider")
class DeterministicToolCallbackProviderTest {

    @Test
    @DisplayName("returns tools sorted by name regardless of the delegate's order")
    void sortsByName() {
        ToolCallback[] unsorted = {fake("zebra"), fake("apple"), fake("mango")};
        var provider = new DeterministicToolCallbackProvider(() -> unsorted);

        assertThat(names(provider)).containsExactly("apple", "mango", "zebra");
    }

    @Test
    @DisplayName("order is stable across repeated calls (deterministic)")
    void stableAcrossCalls() {
        ToolCallback[] callbacks = {fake("c"), fake("a"), fake("b")};
        var provider = new DeterministicToolCallbackProvider(() -> callbacks);

        assertThat(names(provider)).isEqualTo(names(provider)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("returns a fresh array each call so callers cannot corrupt later results")
    void returnsDefensiveCopy() {
        ToolCallback[] callbacks = {fake("b"), fake("a")};
        var provider = new DeterministicToolCallbackProvider(() -> callbacks);

        ToolCallback[] first = provider.getToolCallbacks();
        first[0] = fake("zzz"); // mutate the returned array

        assertThat(names(provider)).containsExactly("a", "b");
    }

    @Test
    @DisplayName("does not mutate the delegate's own array")
    void doesNotMutateDelegate() {
        ToolCallback[] delegateArray = {fake("b"), fake("a")};
        var provider = new DeterministicToolCallbackProvider(() -> delegateArray);

        provider.getToolCallbacks();

        // The delegate's array order is untouched.
        assertThat(delegateArray[0].getToolDefinition().name()).isEqualTo("b");
        assertThat(delegateArray[1].getToolDefinition().name()).isEqualTo("a");
    }

    @Test
    @DisplayName("an empty delegate yields an empty array")
    void emptyDelegate() {
        var provider = new DeterministicToolCallbackProvider(() -> new ToolCallback[0]);
        assertThat(provider.getToolCallbacks()).isEmpty();
    }

    @Test
    @DisplayName("a delegate returning null yields an empty array (no NPE)")
    void nullReturningDelegate() {
        var provider = new DeterministicToolCallbackProvider(() -> null);
        assertThat(provider.getToolCallbacks()).isEmpty();
    }

    @Test
    @DisplayName("a null delegate is rejected at construction")
    void nullDelegateRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DeterministicToolCallbackProvider(null))
                .withMessageContaining("delegate");
    }

    private static java.util.List<String> names(DeterministicToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(c -> c.getToolDefinition().name())
                .toList();
    }

    /** Minimal {@link ToolCallback} whose only meaningful property is its name. */
    private static ToolCallback fake(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("fake tool " + name)
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
        };
    }
}
