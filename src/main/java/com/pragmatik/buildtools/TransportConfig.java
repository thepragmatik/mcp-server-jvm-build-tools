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

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Transport configuration for MCP streamable HTTP support.
 * <p>
 * Configures CORS for web-based MCP clients and dashboards,
 * and provides transport-level settings for the SSE event stream.
 * <p>
 * <b>Secure defaults:</b> cross-origin access is restricted to local origins
 * ({@code http://localhost:8080}, {@code http://127.0.0.1:8080}) unless the
 * operator explicitly widens it via {@code mcp.transport.cors.allowed-origins}.
 * A wildcard ({@code *}) is honoured only when the operator opts in, and is
 * applied through {@code allowedOriginPatterns} so it remains compatible with
 * credentialed requests for local development.
 */
@Configuration
public class TransportConfig {

    /**
     * Comma-separated list of CORS origins permitted to call the {@code /mcp/**}
     * endpoints. Defaults to local origins only (no wildcard). Set this property
     * to widen access for development (e.g. a specific dashboard origin, or
     * {@code *} to allow any origin during local testing).
     */
    @Value("${mcp.transport.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
    private String corsAllowedOrigins = "http://localhost:8080,http://127.0.0.1:8080";

    /**
     * Parses {@link #corsAllowedOrigins} into trimmed, non-empty origin entries.
     *
     * @return the configured origins, or an empty array when none are configured
     */
    String[] parsedAllowedOrigins() {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * @return {@code true} when any configured origin contains a wildcard
     *     ({@code *}), which must be applied via {@code allowedOriginPatterns}
     *     to remain valid alongside {@code allowCredentials(true)}
     */
    boolean usesWildcard() {
        for (String origin : parsedAllowedOrigins()) {
            if (origin.contains("*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * CORS configuration for web-based MCP clients and dashboards.
     * <p>
     * By default only local origins are permitted. Exact origins are registered
     * via {@code allowedOrigins}; wildcard/pattern origins (opt-in) are registered
     * via {@code allowedOriginPatterns}. In production, keep the origin list as
     * narrow as possible and deploy behind a TLS-terminating reverse proxy.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        final String[] origins = parsedAllowedOrigins();
        final boolean wildcard = usesWildcard();
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                var mapping = registry.addMapping("/mcp/**")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders(
                                "Mcp-Method", "Mcp-Session-Id", "Content-Type", "Authorization", "Accept", "Origin")
                        .allowCredentials(true)
                        .maxAge(3600);
                if (wildcard) {
                    mapping.allowedOriginPatterns(origins);
                } else {
                    mapping.allowedOrigins(origins);
                }
            }
        };
    }

    @Value("${buildtools.cache.ttl-ms:300000}")
    private long defaultTtlMs;

    public long getDefaultTtlMs() {
        return defaultTtlMs;
    }
}
