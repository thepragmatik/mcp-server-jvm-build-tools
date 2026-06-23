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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for this server's MCP cacheability policy — the {@code ttlMs} freshness
 * hint and the {@code cacheScope} (public/private) advisory defined by the MCP upcoming-spec
 * {@code CacheableResult} interface (SEP-2549).
 *
 * <h2>What the spec says (SEP-2549)</h2>
 *
 * <blockquote>
 * "Require {@code ttlMs} and {@code cacheScope} fields on results returned by {@code tools/list},
 * {@code prompts/list}, {@code resources/list}, {@code resources/read}, and
 * {@code resources/templates/list} via a new {@code CacheableResult} interface. {@code ttlMs} is a
 * freshness hint (in milliseconds) allowing clients to cache responses and reduce polling;
 * {@code cacheScope} ({@code "public"} or {@code "private"}) controls whether shared intermediaries
 * may cache the response."
 * </blockquote>
 *
 * <h2>Policy chosen for this server</h2>
 *
 * <p>This server's tool/prompt catalogue and resource-template definitions are <b>static for the
 * lifetime of a process</b> — they never change at runtime — so they receive a generous
 * {@link #CATALOGUE_TTL_MS one-hour} {@code ttlMs} and a {@code cacheScope} of
 * {@link #PUBLIC public}: shared gateways may cache them. Per-project resource listings and reads,
 * by contrast, reflect the caller's workspace (build files that can change between builds), so they
 * receive a shorter {@link #PER_PROJECT_TTL_MS one-minute} {@code ttlMs} and a {@code cacheScope}
 * of {@link #PRIVATE private}: shared intermediaries must not cache one caller's project content
 * and serve it to another.
 *
 * <table border="1">
 *   <caption>Cache policy by MCP surface</caption>
 *   <tr><th>Surface</th><th>cacheScope</th><th>ttlMs</th><th>Rationale</th></tr>
 *   <tr><td>{@code tools/list}</td><td>public</td><td>3600000</td><td>static catalogue</td></tr>
 *   <tr><td>{@code prompts/list}</td><td>public</td><td>3600000</td><td>static catalogue</td></tr>
 *   <tr><td>{@code resources/templates/list}</td><td>public</td><td>3600000</td><td>static templates</td></tr>
 *   <tr><td>{@code resources/list}</td><td>private</td><td>60000</td><td>per-project enumeration</td></tr>
 *   <tr><td>{@code resources/read}</td><td>private</td><td>60000</td><td>per-project content</td></tr>
 * </table>
 *
 * <h2>Framework gap (acceptance criterion: document the upstream dependency, ties into #78)</h2>
 *
 * <p>The MCP Java SDK shipped with Spring AI {@code 2.0.0-RC2} is
 * {@code io.modelcontextprotocol.sdk:mcp-core:2.0.0-RC1}, whose result records
 * ({@code McpSchema.ListToolsResult}, {@code ListResourcesResult}, {@code ReadResourceResult},
 * {@code ListResourceTemplatesResult}, {@code ListPromptsResult}) expose only their collection, a
 * {@code nextCursor}, and a {@code _meta} map. <b>There is no {@code CacheableResult} interface and
 * no typed {@code ttlMs}/{@code cacheScope} fields</b> — verified by inspecting the RC1 SDK on the
 * classpath. The native {@code tools/list} / {@code prompts/list} / {@code resources/*} handlers
 * live inside that SDK, so this server cannot stamp the typed fields onto those native results
 * until the SDK adds the interface (tracked alongside the Spring AI RC pin in #78).
 *
 * <p>Until then, this class is the project's single, authoritative cache policy. The server
 * <em>does</em> emit these hints on the surfaces it fully controls today — its discovery surfaces
 * ({@link ServerCardController}, {@link McpDiscoverController}) advertise the catalogue policy, and
 * its own resource list/read tools ({@link DependencyResourceService}) attach a per-result
 * {@code cache} object — so clients and gateways can act on the hints now. When the SDK exposes
 * {@code CacheableResult}, the same constants here can be wired straight onto the native results
 * with no policy change.
 */
public final class McpCachePolicy {

    /** {@code cacheScope} value: shared intermediaries (gateways/proxies) may cache the response. */
    public static final String PUBLIC = "public";

    /** {@code cacheScope} value: only the requesting client may cache; intermediaries must not. */
    public static final String PRIVATE = "private";

    /**
     * Freshness hint for the static catalogue surfaces ({@code tools/list}, {@code prompts/list},
     * {@code resources/templates/list}): one hour. The catalogue never changes during a process, so
     * a generous TTL maximises client/gateway cache hits.
     */
    public static final long CATALOGUE_TTL_MS = 3_600_000L;

    /**
     * Freshness hint for per-project surfaces ({@code resources/list}, {@code resources/read}): one
     * minute. Project build files can change between builds, so the TTL is short to bound staleness
     * while still absorbing rapid re-listing within a single interaction.
     */
    public static final long PER_PROJECT_TTL_MS = 60_000L;

    private McpCachePolicy() {
        // Utility class: static policy only.
    }

    /** Cache policy for {@code tools/list}: a static catalogue, publicly cacheable for one hour. */
    public static Map<String, Object> toolsList() {
        return cache(CATALOGUE_TTL_MS, PUBLIC);
    }

    /** Cache policy for {@code prompts/list}: a static catalogue, publicly cacheable for one hour. */
    public static Map<String, Object> promptsList() {
        return cache(CATALOGUE_TTL_MS, PUBLIC);
    }

    /**
     * Cache policy for {@code resources/templates/list}: static template definitions, publicly
     * cacheable for one hour.
     */
    public static Map<String, Object> resourceTemplatesList() {
        return cache(CATALOGUE_TTL_MS, PUBLIC);
    }

    /**
     * Cache policy for {@code resources/list}: a per-project enumeration, privately cacheable for one
     * minute.
     */
    public static Map<String, Object> resourcesList() {
        return cache(PER_PROJECT_TTL_MS, PRIVATE);
    }

    /**
     * Cache policy for {@code resources/read}: per-project content, privately cacheable for one
     * minute.
     */
    public static Map<String, Object> resourcesRead() {
        return cache(PER_PROJECT_TTL_MS, PRIVATE);
    }

    /**
     * The complete, per-surface cache policy this server advertises on its discovery surfaces (the
     * server card and {@code server/discover}). Keyed by MCP method name, each value is the
     * {@code {"ttlMs": ..., "cacheScope": ...}} fragment that the corresponding result carries (or
     * would carry, once the SDK exposes {@code CacheableResult}). A fresh map is returned on every
     * call so callers may embed it without mutating shared state.
     *
     * @return an ordered, mutable map of MCP method name to its cache fragment
     */
    public static Map<String, Object> advertisedPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("tools/list", toolsList());
        policy.put("prompts/list", promptsList());
        policy.put("resources/list", resourcesList());
        policy.put("resources/read", resourcesRead());
        policy.put("resources/templates/list", resourceTemplatesList());
        return policy;
    }

    /**
     * Builds a fresh, mutable {@code CacheableResult} fragment ({@code {"ttlMs": ..., "cacheScope":
     * ...}}). A new map is returned on every call so callers may embed or extend it without mutating
     * shared state.
     *
     * @param ttlMs the freshness hint in milliseconds (must be {@code >= 0})
     * @param cacheScope {@link #PUBLIC} or {@link #PRIVATE}
     * @return an ordered, mutable map carrying the two cache fields
     */
    public static Map<String, Object> cache(long ttlMs, String cacheScope) {
        if (ttlMs < 0) {
            throw new IllegalArgumentException("ttlMs must not be negative: " + ttlMs);
        }
        if (!PUBLIC.equals(cacheScope) && !PRIVATE.equals(cacheScope)) {
            throw new IllegalArgumentException("cacheScope must be \"public\" or \"private\": " + cacheScope);
        }
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("ttlMs", ttlMs);
        cache.put("cacheScope", cacheScope);
        return cache;
    }
}
