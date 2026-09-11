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

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.Assert;

/**
 * Builds the deterministic <b>tool catalogue summary</b> advertised under the additive
 * {@code tools} key of the {@code server/discover} result (mcp-005 slice 2, issue #177;
 * design rationale in {@code docs/mcp-005-research.md}, Option B).
 *
 * <p>The summary is driven entirely by {@link DeterministicToolCallbackProvider}: the wrapped
 * provider returns the catalogue in stable, name-sorted order (SEP-2549 deterministic order),
 * so the summary is a pure function of the static catalogue and is identical on every call —
 * count, names, and service grouping can never drift from what {@code tools/list} actually
 * serves.
 *
 * <h2>Service grouping</h2>
 *
 * <p>Tools are grouped by their owning service, resolved from the {@code @Tool}-annotated
 * methods of the registered tool objects: the map from MCP tool name to declaring service is
 * built once at construction (each {@code @Tool} method's {@code name} maps to the decapitalised
 * simple class name of its declaring bean, e.g. {@code BuildToolsService} →
 * {@code "buildToolsService"}). Every registered tool groups under exactly one service; a tool
 * name not present in the map (unregistered or provider-added) falls back to the
 * {@link #UNGROUPED} key so the summary stays total. Generic service names carry no secrets
 * (issue #177 constraint: tool names and service group names only).
 *
 * <h2>Summary modes</h2>
 *
 * Bound to {@code buildtools.discover.tools-summary} ({@code none | count | full}, default
 * {@code full}) via the same {@code @Value}-with-literal-fallback pattern as
 * {@code McpServerIdentity}, so directly-constructed instances and the property-bound runtime
 * agree on the default:
 *
 * <ul>
 *   <li>{@code none} — the summary is not emitted at all; the discover result keeps its exact
 *       legacy shape (deployments can shed payload size without a code change).</li>
 *   <li>{@code count} — only {@code {"count": N}} is advertised.</li>
 *   <li>{@code full} — {@code {"count": N, "names": [...], "groups": {...}}}.</li>
 * </ul>
 *
 * <p>Because {@code /mcp/discover} is a pre-auth surface (exempt from bearer enforcement in
 * {@code OAuthResourceServerFilter}), the summary deliberately contains only tool names and
 * generic service group names — never descriptions, schemas, or credentials.
 */
public final class ToolCatalogueSummary {

    /** Group key for a tool whose owning service cannot be resolved. */
    public static final String UNGROUPED = "ungrouped";

    /** Summary advertisement level, bound from {@code buildtools.discover.tools-summary}. */
    public enum Mode {

        /** Emit nothing — the discover result keeps its exact legacy shape. */
        NONE,

        /** Advertise only the tool count. */
        COUNT,

        /** Advertise count, deterministic name list, and service grouping. */
        FULL;

        /** Parses a config value; an unrecognised or absent value falls back to {@link #FULL}. */
        public static Mode fromConfig(String value) {
            if (value == null) {
                return FULL;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "none" -> NONE;
                case "count" -> COUNT;
                default -> FULL;
            };
        }
    }

    private final ToolCallbackProvider toolCallbackProvider;
    private final Map<String, String> serviceByToolName;
    private final Mode mode;

    /**
     * @param toolCallbackProvider the deterministic provider backing the summary; must not be
     *     {@code null}
     * @param toolObjects the {@code @Tool}-annotated service beans registered in the provider;
     *     must not be {@code null} but may be empty
     * @param mode what to advertise (never {@code null}); {@link Mode#NONE} produces an empty
     *     map from {@link #summary()}
     */
    public ToolCatalogueSummary(ToolCallbackProvider toolCallbackProvider, List<Object> toolObjects, Mode mode) {
        Assert.notNull(toolCallbackProvider, "toolCallbackProvider must not be null");
        Assert.notNull(toolObjects, "toolObjects must not be null");
        Assert.notNull(mode, "mode must not be null");
        this.toolCallbackProvider = toolCallbackProvider;
        this.serviceByToolName = Map.copyOf(serviceGroups(toolObjects));
        this.mode = mode;
    }

    /** Convenience constructor binding {@link Mode#FULL} (the documented config default). */
    public ToolCatalogueSummary(ToolCallbackProvider toolCallbackProvider, List<Object> toolObjects) {
        this(toolCallbackProvider, toolObjects, Mode.FULL);
    }

    /**
     * Builds the {@code tools} summary object for the discover result.
     *
     * <ul>
     *   <li>{@code none}: an empty map — callers emit no {@code tools} key (exact legacy
     *       payload).</li>
     *   <li>{@code count}: {@code {"count": N}} only.</li>
     *   <li>{@code full}: {@code {"count": N, "names": [...], "groups": {service: [names]}}}.</li>
     * </ul>
     *
     * @return an ordered, fresh summary map (never {@code null})
     */
    public Map<String, Object> summary() {
        if (mode == Mode.NONE) {
            return Map.of();
        }

        List<String> names = catalogueNames();
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("count", names.size());
        if (mode == Mode.COUNT) {
            return summary;
        }

        summary.put("names", List.copyOf(names));
        summary.put("groups", groups(names));
        return summary;
    }

    /** The summary level in effect. */
    public Mode mode() {
        return mode;
    }

    /**
     * The catalogue's tool names in the provider's deterministic order. The provider already
     * sorts by name; the re-sort here is a cheap defensive guarantee that the summary's order is
     * a pure function of the names.
     *
     * @return the sorted tool names
     */
    private List<String> catalogueNames() {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .sorted()
                .toList();
    }

    /**
     * Groups the given tool names by their owning service (resolved from the tool name → service
     * map built at construction). Group keys appear in first-seen order over the deterministic
     * name-sorted catalogue; each group's members keep that deterministic order.
     *
     * @param names the deterministic tool names to group
     * @return an ordered map of service group name to that service's tool names
     */
    private Map<String, List<String>> groups(List<String> names) {
        return names.stream()
                .collect(Collectors.groupingBy(
                        name -> serviceByToolName.getOrDefault(name, UNGROUPED),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
    }

    /**
     * Builds the tool-name → service-name map by scanning each tool object's declared methods
     * for {@link Tool @Tool} annotations. The service key is the decapitalised simple class name
     * of the declaring bean.
     *
     * @param toolObjects the registered {@code @Tool}-annotated service beans
     * @return an ordered map of MCP tool name to service group name
     */
    private static Map<String, String> serviceGroups(List<Object> toolObjects) {
        Map<String, String> serviceByToolName = new LinkedHashMap<>();
        for (Object toolObject : toolObjects) {
            String service = Introspector.decapitalize(toolObject.getClass().getSimpleName());
            for (Method method : toolObject.getClass().getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null && !tool.name().isBlank()) {
                    serviceByToolName.putIfAbsent(tool.name(), service);
                }
            }
        }
        return serviceByToolName;
    }
}
