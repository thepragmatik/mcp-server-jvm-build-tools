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

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for this server's <b>OAuth 2.1 resource-server</b> profile, as
 * required by the MCP authorization spec (RFC9728 Protected Resource Metadata + RFC6750
 * {@code WWW-Authenticate} challenges).
 *
 * <p>The HTTP transport advertises itself as an OAuth 2.1 resource server in an
 * <b>additive, backward-compatible</b> way:
 *
 * <ul>
 *   <li>The Protected Resource Metadata document is served unconditionally at
 *       {@link #PROTECTED_RESOURCE_METADATA_PATH} ({@link OAuthProtectedResourceMetadataController})
 *       so OAuth-capable clients can discover the resource server; this is a new, purely
 *       additive endpoint that existing clients simply ignore.</li>
 *   <li>Bearer-token <b>enforcement</b> on {@code /mcp/**} ({@link OAuthResourceServerFilter})
 *       is <b>opt-in</b> via {@code buildtools.oauth.resource-server.enabled} (default
 *       {@code false}). With enforcement off — the default — no client behaviour changes;
 *       requests pass through exactly as before.</li>
 * </ul>
 *
 * <p>The advertised {@code scopes_supported} are this server's fine-grained
 * {@link ToolPermission} scopes. Per the spec, {@code offline_access} (and the internal
 * {@code *} wildcard) are never advertised.
 *
 * <p>Both the metadata controller and the enforcement filter resolve their configuration
 * from this one bean so the advertised metadata, the {@code resource_metadata} URL in the
 * {@code WWW-Authenticate} challenge, and the enforced resource cannot drift.
 */
@Component
public class OAuthResourceServerConfig {

    /** RFC9728 well-known path at which Protected Resource Metadata is published. */
    public static final String PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    /** OAuth scope this server must never advertise (per the MCP authorization spec). */
    static final String OFFLINE_ACCESS_SCOPE = "offline_access";

    /** Internal wildcard scope — never advertised as an externally requestable OAuth scope. */
    static final String WILDCARD_SCOPE = "*";

    private final boolean enabled;
    private final String configuredResource;
    private final List<String> authorizationServers;

    /**
     * Spring injection point.
     *
     * @param enabled whether bearer-token enforcement on {@code /mcp/**} is active (default
     *     {@code false}; metadata is published regardless)
     * @param configuredResource the canonical resource identifier this server protects; when blank
     *     it is derived per-request from the incoming request URL plus the {@code /mcp} suffix
     * @param authorizationServers comma-separated OAuth authorization-server issuer URLs that may
     *     mint tokens for this resource (optional; omitted from the metadata when empty)
     */
    @Autowired
    public OAuthResourceServerConfig(
            @Value("${buildtools.oauth.resource-server.enabled:false}") boolean enabled,
            @Value("${buildtools.oauth.resource:}") String configuredResource,
            @Value("${buildtools.oauth.authorization-servers:}") String authorizationServers) {
        this.enabled = enabled;
        this.configuredResource = configuredResource == null ? "" : configuredResource.trim();
        this.authorizationServers = parseCsv(authorizationServers);
    }

    /**
     * Convenience constructor for unit tests.
     *
     * @param enabled whether bearer-token enforcement is active
     * @param configuredResource the canonical resource identifier ({@code ""}/{@code null} to derive)
     * @param authorizationServers the authorization-server issuer URLs (may be empty)
     */
    public OAuthResourceServerConfig(boolean enabled, String configuredResource, List<String> authorizationServers) {
        this.enabled = enabled;
        this.configuredResource = configuredResource == null ? "" : configuredResource.trim();
        this.authorizationServers = authorizationServers == null ? List.of() : List.copyOf(authorizationServers);
    }

    /**
     * @return {@code true} when bearer-token enforcement on {@code /mcp/**} is active. When
     *     {@code false} (the default), the server is fully backward compatible: it still publishes
     *     Protected Resource Metadata but never challenges or rejects a request for a missing token.
     */
    public boolean enforcementEnabled() {
        return enabled;
    }

    /**
     * The OAuth authorization servers permitted to mint access tokens for this resource.
     *
     * @return an immutable, possibly empty list of issuer URLs
     */
    public List<String> authorizationServers() {
        return authorizationServers;
    }

    /**
     * The fine-grained scopes this resource server understands, suitable for the metadata
     * {@code scopes_supported} field. Derived from {@link ToolPermission}; the internal
     * {@code *} wildcard and {@code offline_access} are never included.
     *
     * @return an immutable, ordered list of advertisable scopes
     */
    public List<String> scopesSupported() {
        List<String> scopes = new ArrayList<>();
        for (String scope : ToolPermission.allScopes()) {
            if (scope == null) {
                continue;
            }
            String normalized = scope.trim();
            if (normalized.isEmpty()
                    || WILDCARD_SCOPE.equals(normalized)
                    || OFFLINE_ACCESS_SCOPE.equalsIgnoreCase(normalized)) {
                continue;
            }
            scopes.add(normalized);
        }
        return List.copyOf(scopes);
    }

    /**
     * The canonical resource identifier this server protects. When configured explicitly
     * ({@code buildtools.oauth.resource}) that value is returned verbatim; otherwise it is derived
     * from the incoming request as {@code <scheme>://<host>[:<port>]<contextPath>/mcp}.
     *
     * @param request the current request, used only when no explicit resource is configured
     * @return the resource identifier
     */
    public String resourceIdentifier(HttpServletRequest request) {
        if (!configuredResource.isEmpty()) {
            return configuredResource;
        }
        return baseUrl(request) + "/mcp";
    }

    /**
     * The absolute URL of this server's Protected Resource Metadata document, used both as the
     * canonical metadata location and as the {@code resource_metadata} parameter of the
     * {@code WWW-Authenticate} challenge.
     *
     * @param request the current request
     * @return the absolute metadata URL
     */
    public String metadataUrl(HttpServletRequest request) {
        return baseUrl(request) + PROTECTED_RESOURCE_METADATA_PATH;
    }

    /**
     * Builds the scheme/host/port/context-path prefix of the current request. Behind a
     * TLS-terminating reverse proxy, set {@code buildtools.oauth.resource} explicitly (or enable
     * Spring's {@code ForwardedHeaderFilter}) so the advertised URLs reflect the external origin.
     *
     * @param request the current request
     * @return the request's origin prefix, without a trailing slash
     */
    static String baseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        StringBuilder url = new StringBuilder();
        url.append(scheme).append("://").append(host);
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        if (port > 0 && !defaultPort) {
            url.append(':').append(port);
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)) {
            url.append(contextPath);
        }
        return url.toString();
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
