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

import com.pragmatik.buildtools.security.OAuthResourceServerConfig;
import com.pragmatik.buildtools.security.ProjectAccessPolicy;
import com.pragmatik.buildtools.security.ToolAuthorizationService;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Transport configuration for the MCP <b>stateless Streamable HTTP</b> transport.
 * <p>
 * Configures CORS for web-based MCP clients and dashboards.
 * <p>
 * The active SDK transport is stateless and implements the 2025-11-25 MCP
 * protocol revision. Draft 2026 headers are permitted for compatibility,
 * but they are not a conformance claim.
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
     * Single source of truth for the default CORS origin list (local origins only,
     * no wildcard). Used both as the {@code @Value} fallback (when Spring resolves
     * the property) and as the field initializer (the no-Spring fallback the unit
     * tests assert against), so the two cannot drift.
     */
    static final String DEFAULT_ALLOWED_ORIGINS = "http://localhost:8080,http://127.0.0.1:8080";

    @Bean
    @Profile("http")
    public SmartInitializingSingleton httpExposureGuard(
            @Value("${server.address:127.0.0.1}") String address,
            ProjectAccessPolicy projects,
            OAuthResourceServerConfig auth,
            ToolAuthorizationService credentials) {
        return () -> {
            if (!isLoopback(address)) {
                if (projects.roots().isEmpty()
                        || !auth.enforcementEnabled()
                        || !credentials.hasConfiguredCredentials()
                        || usesWildcard()) {
                    throw new IllegalStateException(
                            "Non-loopback HTTP requires project roots, bearer authentication, configured credentials, and restricted CORS");
                }
            }
        };
    }

    static boolean isLoopback(String address) {
        return "127.0.0.1".equalsIgnoreCase(address)
                || "localhost".equalsIgnoreCase(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    /**
     * Comma-separated list of CORS origins permitted to call the {@code /mcp/**}
     * endpoints. Defaults to local origins only (no wildcard). Set this property
     * to widen access for development (e.g. a specific dashboard origin, or
     * {@code *} to allow any origin during local testing).
     */
    @Value("${mcp.transport.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String corsAllowedOrigins = DEFAULT_ALLOWED_ORIGINS;

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
        return containsWildcard(parsedAllowedOrigins());
    }

    /**
     * @param origins already-parsed origin entries
     * @return {@code true} when any entry contains a wildcard ({@code *})
     */
    private static boolean containsWildcard(String[] origins) {
        for (String origin : origins) {
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
        final boolean wildcard = containsWildcard(origins);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                var mapping = registry.addMapping("/mcp/**")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        // 2026-07-28 RC: advertise the standard MCP request headers
                        // (Mcp-Method, Mcp-Name — SEP-2243). The session header
                        // (Mcp-Session-Id) was removed with protocol-level sessions
                        // (SEP-2575) and is intentionally NOT advertised here.
                        .allowedHeaders("Mcp-Method", "Mcp-Name", "Content-Type", "Authorization", "Accept", "Origin")
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

    /** Servlet transport bypasses MVC, so its endpoint needs a servlet CORS filter. */
    @Bean
    @Profile("http")
    public FilterRegistrationBean<CorsFilter> mcpCorsFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        String[] origins = parsedAllowedOrigins();
        if (containsWildcard(origins)) {
            cors.setAllowedOriginPatterns(List.of(origins));
        } else {
            cors.setAllowedOrigins(List.of(origins));
        }
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Mcp-Method", "Mcp-Name", "Content-Type", "Authorization", "Accept", "Origin"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // The URL registration limits this filter to MCP paths. With an exact
        // servlet mapping the lookup path is servlet-relative, often empty.
        source.registerCorsConfiguration("/**", cors);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
