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

import java.util.Arrays;
import java.util.Comparator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.util.Assert;

/**
 * A {@link ToolCallbackProvider} decorator that returns the wrapped provider's tools in a
 * <b>stable, deterministic order</b> — sorted by tool name — on every call.
 *
 * <h2>Why this exists (MCP RC, SEP-2549)</h2>
 *
 * <p>The MCP upcoming-spec changelog recommends:
 *
 * <blockquote>
 * "Servers SHOULD return tools from {@code tools/list} in a deterministic order to enable
 * client-side caching and improve LLM prompt cache hit rates."
 * </blockquote>
 *
 * <p>This server's tool catalogue is static for the lifetime of a process, which makes it an
 * ideal candidate for client/gateway caching — but only if the order is stable. The underlying
 * {@link org.springframework.ai.tool.method.MethodToolCallbackProvider} discovers {@code @Tool}
 * methods via reflection, and {@link Class#getDeclaredMethods()} makes <em>no ordering
 * guarantee</em> across JVMs or runs. Without normalisation, {@code tools/list} could return the
 * same catalogue in a different order on a different host or after a restart, defeating prompt
 * caches even though nothing actually changed.
 *
 * <p>Sorting by tool name at the provider boundary makes the catalogue order a pure function of
 * the tool names, so any MCP runtime that builds {@code tools/list} from
 * {@link #getToolCallbacks()} emits a stable order. MCP tool names are unique, so ordering by
 * name is a total order and therefore fully deterministic.
 *
 * <h2>Backward compatibility</h2>
 *
 * <p>Reordering the catalogue is purely additive from a client's perspective: the same set of
 * tools with the same definitions is returned — only the iteration order is normalised. No tool
 * is added, removed, renamed, or otherwise changed.
 */
public final class DeterministicToolCallbackProvider implements ToolCallbackProvider {

    /** Orders tools by their (unique) MCP name, giving a total, stable order. */
    private static final Comparator<ToolCallback> BY_TOOL_NAME =
            Comparator.comparing(callback -> callback.getToolDefinition().name());

    private final ToolCallbackProvider delegate;

    /**
     * @param delegate the provider whose tools should be returned in deterministic order; must not
     *     be {@code null}
     */
    public DeterministicToolCallbackProvider(ToolCallbackProvider delegate) {
        Assert.notNull(delegate, "delegate ToolCallbackProvider must not be null");
        this.delegate = delegate;
    }

    /**
     * Returns the delegate's tools sorted by name. A fresh array is produced on every call, so the
     * caller can freely mutate the returned array without affecting the delegate or subsequent
     * calls.
     *
     * @return the tool callbacks in stable, name-sorted order (never {@code null})
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] callbacks = delegate.getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] sorted = callbacks.clone();
        Arrays.sort(sorted, BY_TOOL_NAME);
        return sorted;
    }
}
