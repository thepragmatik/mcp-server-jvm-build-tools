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
package com.pragmatik.buildtools.security;

import com.pragmatik.buildtools.application.McpServerIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OAuth 2.0 Protected Resource Metadata endpoint (RFC9728) for enforced bearer mode
 * with configured issuers.
 *
 * <p>Exposed at {@code GET /.well-known/oauth-protected-resource} when the Streamable HTTP
 * transport is active. The document lets OAuth-capable MCP clients discover the resource
 * server (its identifier, the authorization servers that may mint tokens for it, and the
 * scopes it understands) without first connecting via the MCP protocol — mirroring the
 * existing {@code /.well-known/mcp-server} server card.
 *
 * <p>Without an issuer or active enforcement this endpoint returns 404 rather than claiming
 * OAuth discovery with an unusable document. Issuer configuration enables the
 * document but does not enable validation of issuer-issued tokens; the deployment must arrange
 * that separately.
 *
 * <h2>Spec conformance</h2>
 *
 * <ul>
 *   <li>{@code resource} — the canonical resource identifier this server protects
 *       ({@link OAuthResourceServerConfig#resourceIdentifier(HttpServletRequest)}).</li>
 *   <li>{@code authorization_servers} — at least one configured issuer.</li>
 *   <li>{@code scopes_supported} — this server's fine-grained {@link ToolPermission} scopes.
 *       Per the spec, {@code offline_access} is never advertised.</li>
 *   <li>{@code bearer_methods_supported} — {@code ["header"]}; tokens are carried in the
 *       {@code Authorization: Bearer} header (RFC6750).</li>
 * </ul>
 */
@RestController
public class OAuthProtectedResourceMetadataController {

    /**
     * Where operators and clients can read this server's authorization posture and threat model.
     */
    static final String RESOURCE_DOCUMENTATION =
            "https://github.com/thepragmatik/mcp-server-jvm-build-tools/blob/main/docs/AUTHORIZATION.md";

    private final OAuthResourceServerConfig config;
    private final McpServerIdentity identity;

    public OAuthProtectedResourceMetadataController(OAuthResourceServerConfig config, McpServerIdentity identity) {
        this.config = config;
        this.identity = identity;
    }

    /**
     * Serves the RFC9728 Protected Resource Metadata document.
     *
     * @param request the current request, used to derive per-deployment URLs when no explicit
     *     {@code buildtools.oauth.resource} is configured
     * @return an ordered map rendered as the metadata JSON object
     */
    @GetMapping(
            value = OAuthResourceServerConfig.PROTECTED_RESOURCE_METADATA_PATH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> protectedResourceMetadata(HttpServletRequest request) {
        if (!config.oauthDiscoveryEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put("resource", config.resourceIdentifier(request));

        List<String> authorizationServers = config.authorizationServers();
        metadata.put("authorization_servers", authorizationServers);

        metadata.put("scopes_supported", config.scopesSupported());
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", identity.name());
        metadata.put("resource_documentation", RESOURCE_DOCUMENTATION);

        return metadata;
    }
}
