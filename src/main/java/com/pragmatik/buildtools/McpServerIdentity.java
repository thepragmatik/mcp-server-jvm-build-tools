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
import java.util.List;
import java.util.Map;
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

    private final String name;
    private final String version;

    public McpServerIdentity(
            @Value("${spring.ai.mcp.server.name:${spring.application.name:mcp-server-jvm-build-tools}}") String name,
            @Value("${spring.ai.mcp.server.version:${buildtools.version:0.1.1-SNAPSHOT}}") String version) {
        this.name = name;
        this.version = version;
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
}
