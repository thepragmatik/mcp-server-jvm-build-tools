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
package com.pragmatik.buildtools.application;

import com.pragmatik.buildtools.transport.McpDiscoverController;
import com.pragmatik.buildtools.transport.McpHeaderValidationFilter;
import com.pragmatik.buildtools.transport.ServerCardController;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for this server's MCP identity and its advertised
 * protocol/transport/capability profile.
 * <p>
 * Every discovery surface — the {@code /.well-known/mcp-server} card
 * ({@link ServerCardController}), the {@code server/discover} RPC
 * ({@link McpDiscoverController}), and the {@code Mcp-Name} HeaderMismatch check
 * ({@link McpHeaderValidationFilter}) — resolves its name, version, vendor,
 * supported protocol versions, transport profile, and capabilities from this one
 * bean. Centralising them here guarantees the surfaces can never drift: a client
 * that reads the server card and echoes its {@code name} as {@code Mcp-Name} on a
 * {@code POST /mcp/**} request always matches, because all three read the exact
 * same value.
 * <p>
 * Name and version are resolved from the same Spring properties the Spring AI MCP
 * server starter uses ({@code spring.ai.mcp.server.name} /
 * {@code spring.ai.mcp.server.version}), with sensible fallbacks, so the value
 * reported over the MCP protocol and the values published by these HTTP surfaces
 * are identical.
 */
@Component
public class McpServerIdentity {

    /** Vendor advertised across every discovery surface. */
    public static final String VENDOR = "The Pragmatik";

    /** Protocol versions this server can speak (oldest to newest). */
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2024-11-05", "2025-03-26", "2026-07-28");

    /** MCP {@code cacheScope} value permitting shared intermediaries to cache a response. */
    public static final String CACHE_SCOPE_PUBLIC = "public";

    /** MCP {@code cacheScope} value restricting caching to the requesting client. */
    public static final String CACHE_SCOPE_PRIVATE = "private";

    /**
     * Default freshness hint (24h) for the static catalogue surfaces ({@code tools/list},
     * {@code prompts/list}, {@code resources/list}, {@code resources/templates/list}). The tool set
     * does not change for the lifetime of the process, so a generous TTL is safe and maximises cache
     * hit rates. Overridable via {@code buildtools.cache.catalog-ttl-ms}.
     */
    public static final long DEFAULT_CATALOG_TTL_MS = 86_400_000L;

    /**
     * Default freshness hint (5min) for per-project content reads ({@code resources/read}). Reads
     * reflect on-disk project state and may change between calls, so the TTL is short and the scope
     * is private. Overridable via {@code buildtools.cache.read-ttl-ms}.
     */
    public static final long DEFAULT_READ_TTL_MS = 300_000L;

    private final String name;
    private final String version;

    /**
     * Freshness hint (ms) advertised for the static catalogue list surfaces. Bound via the
     * constructor's {@code @Value} fallback to {@link #DEFAULT_CATALOG_TTL_MS} so unit tests
     * (constructed without Spring) and the property-bound runtime agree on the default.
     */
    private final long catalogTtlMs;

    /**
     * Freshness hint (ms) advertised for per-project content reads. Bound via the constructor's
     * {@code @Value} fallback to {@link #DEFAULT_READ_TTL_MS} so unit tests and the property-bound
     * runtime agree on the default.
     */
    private final long readTtlMs;

    /**
     * Spring injection point. Every value binds from a property with a literal fallback, so a
     * directly-constructed instance and the property-bound runtime share identical defaults. All
     * fields are {@code final} and injected through this single constructor, matching the
     * constructor-injection style used for {@code name}/{@code version}.
     */
    @Autowired
    public McpServerIdentity(
            @Value("${spring.ai.mcp.server.name:${spring.application.name:mcp-server-jvm-build-tools}}") String name,
            @Value("${spring.ai.mcp.server.version:${buildtools.version:0.1.1-SNAPSHOT}}") String version,
            @Value("${buildtools.cache.catalog-ttl-ms:" + DEFAULT_CATALOG_TTL_MS + "}") long catalogTtlMs,
            @Value("${buildtools.cache.read-ttl-ms:" + DEFAULT_READ_TTL_MS + "}") long readTtlMs) {
        this.name = name;
        this.version = version;
        this.catalogTtlMs = catalogTtlMs;
        this.readTtlMs = readTtlMs;
    }

    /**
     * Convenience constructor for unit tests that do not override the cache TTLs: binds
     * {@code name}/{@code version} explicitly and the cache TTLs to their documented defaults
     * ({@link #DEFAULT_CATALOG_TTL_MS} / {@link #DEFAULT_READ_TTL_MS}). Not a Spring injection point
     * — the {@code @Autowired} four-argument constructor is the bean's injection point.
     */
    public McpServerIdentity(String name, String version) {
        this(name, version, DEFAULT_CATALOG_TTL_MS, DEFAULT_READ_TTL_MS);
    }

    /** The canonical server name advertised on every surface (and required by {@code Mcp-Name}). */
    public String name() {
        return name;
    }

    /** The canonical server version advertised on every surface. */
    public String version() {
        return version;
    }

    /** The vendor advertised on every surface. */
    public String vendor() {
        return VENDOR;
    }

    /** The supported protocol versions (oldest to newest). */
    public List<String> protocolVersions() {
        return SUPPORTED_PROTOCOL_VERSIONS;
    }

    /** The newest protocol version this server prefers, for clients that want a single value. */
    public String latestProtocolVersion() {
        return SUPPORTED_PROTOCOL_VERSIONS.get(SUPPORTED_PROTOCOL_VERSIONS.size() - 1);
    }

    /**
     * A fresh, mutable description of the transport characteristics (2026-07-28 RC:
     * stateless Streamable HTTP — no sessions, no SSE resumability). A new map is
     * returned on each call so callers may add surface-specific keys without
     * mutating shared state.
     *
     * @return an ordered, mutable transport-profile map
     */
    public Map<String, Object> transportProfile() {
        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("type", "streamable-http");
        transport.put("stateless", true);
        transport.put("sessions", false);
        transport.put("sseResumability", false);
        return transport;
    }

    /**
     * A fresh, mutable map of the core MCP capabilities, expressed as MCP capability
     * <i>objects</i> ({@code {}} means "supported"). This is the single shape used by
     * both the server card and {@code server/discover}, so the two cannot describe the
     * same capability with different structures. A new map is returned on each call so
     * a surface may layer in additional, surface-specific entries (e.g. the card's
     * {@code logging}/{@code extensions}) without mutating shared state.
     *
     * @return an ordered, mutable capabilities map
     */
    public Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        capabilities.put("resources", Map.of("listChanged", false, "subscribe", false));
        capabilities.put("prompts", Map.of("listChanged", false));
        return capabilities;
    }

    /**
     * The recommended caching policy for this server's MCP list/read surfaces, keyed by MCP method
     * name. Each entry carries an {@code ttlMs} freshness hint (in milliseconds) and a
     * {@code cacheScope} ({@code "public"} or {@code "private"}), mirroring the {@code CacheableResult}
     * interface of the MCP upcoming spec (SEP-2549).
     *
     * <p>Because this server's tool/prompt/resource catalogue is static for the lifetime of the
     * process, the list surfaces advertise a generous TTL and {@code "public"} scope so clients and
     * shared gateways may cache them aggressively; per-project content reads advertise a short TTL and
     * {@code "private"} scope because they reflect mutable on-disk state.
     *
     * <p><b>Why this is advertised here rather than emitted per result:</b> the bundled MCP SDK
     * ({@code io.modelcontextprotocol.sdk} {@code 2.0.0-RC1}, via {@code spring-ai-mcp} {@code
     * 2.0.0-RC2}) does not yet model {@code ttlMs}/{@code cacheScope} as typed fields on its result
     * records, so the server cannot emit spec-level {@code CacheableResult} fields on individual
     * responses today. Advertising the policy on the discovery surfaces (server card and
     * {@code server/discover}) is an additive, backward-compatible interim: existing clients ignore
     * the unknown key, while cache-aware clients/gateways can learn the catalogue is cacheable. See
     * {@code docs/mcp-cacheable-result-gap.md} for the upstream dependency.
     *
     * <p>A fresh map is returned on each call so callers may layer in surface-specific entries
     * without mutating shared state.
     *
     * @return an ordered, mutable map of MCP method name to its {@code {ttlMs, cacheScope}} hint
     */
    public Map<String, Object> cacheHints() {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("tools/list", hint(catalogTtlMs, CACHE_SCOPE_PUBLIC));
        hints.put("prompts/list", hint(catalogTtlMs, CACHE_SCOPE_PUBLIC));
        hints.put("resources/list", hint(catalogTtlMs, CACHE_SCOPE_PUBLIC));
        hints.put("resources/templates/list", hint(catalogTtlMs, CACHE_SCOPE_PUBLIC));
        hints.put("resources/read", hint(readTtlMs, CACHE_SCOPE_PRIVATE));
        return hints;
    }

    /**
     * Builds a single {@code CacheableResult}-shaped hint.
     *
     * @param ttlMs the freshness hint in milliseconds
     * @param cacheScope {@link #CACHE_SCOPE_PUBLIC} or {@link #CACHE_SCOPE_PRIVATE}
     * @return an ordered, immutable {@code {ttlMs, cacheScope}} map
     */
    private static Map<String, Object> hint(long ttlMs, String cacheScope) {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("ttlMs", ttlMs);
        hint.put("cacheScope", cacheScope);
        return Collections.unmodifiableMap(hint);
    }
}
