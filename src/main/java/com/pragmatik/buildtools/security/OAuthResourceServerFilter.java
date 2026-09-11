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

import com.pragmatik.buildtools.tool.JsonUtils;
import com.pragmatik.buildtools.transport.McpHeaderValidationFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces the MCP server's role as an <b>OAuth 2.1 resource server</b> on the Streamable HTTP
 * transport: it validates the {@code Authorization: Bearer} access token (RFC6750) presented on
 * {@code POST/GET /mcp/**} and, when a token is missing or invalid, replies {@code 401} with a
 * {@code WWW-Authenticate: Bearer resource_metadata="..."} challenge that points at this server's
 * RFC9728 Protected Resource Metadata ({@link OAuthProtectedResourceMetadataController}).
 *
 * <h2>Backward compatibility (opt-in)</h2>
 *
 * Enforcement is <b>disabled by default</b> ({@code buildtools.oauth.resource-server.enabled=false}).
 * With it off, this filter is inert — every request passes through untouched, so existing MCP
 * clients (which present no {@code Authorization} header) are entirely unaffected. Only when an
 * operator explicitly opts in does the filter begin challenging unauthenticated requests. The
 * Protected Resource Metadata document is published regardless of this flag.
 *
 * <h2>Discovery stays reachable</h2>
 *
 * The {@code server/discover} probe ({@code /mcp/discover}) is exempt from enforcement: a client
 * must be able to negotiate protocol versions and learn the metadata location <i>before</i> it can
 * obtain a token. The {@code .well-known} discovery endpoints live outside {@code /mcp/**} and are
 * therefore never touched by this filter.
 *
 * <h2>Token validation</h2>
 *
 * Tokens are validated locally (RFC7662-style introspection against the configured credential
 * store, {@link ToolAuthorizationService#isAccessTokenValid(String)}). This server's resource-server
 * profile uses opaque bearer tokens minted out of band as {@code BUILDTOOLS_API_KEY_*} credentials;
 * see {@code docs/AUTHORIZATION.md} for the recorded posture, including delegating full
 * authorization-server / JWT validation to a fronting OAuth gateway.
 *
 * <p>Ordered ahead of {@link McpHeaderValidationFilter} (authentication precedes request-header
 * semantics). Inert in the default stdio mode, where there is no servlet container.
 */
@Component
@Order(1)
public class OAuthResourceServerFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(OAuthResourceServerFilter.class);

    /** Path prefix of the MCP Streamable HTTP transport endpoints. */
    static final String MCP_PATH_PREFIX = "/mcp/";

    /** The MCP protocol endpoint itself ({@code POST /mcp}), subject to the same exemption rules. */
    static final String MCP_PROTOCOL_PATH = "/mcp";

    /** Discovery probe path, exempt from enforcement so clients can learn how to authenticate. */
    static final String DISCOVER_PATH = "/mcp/discover";

    /** The JSON-RPC discover method: a pre-auth surface on any MCP transport path (SEP-2575). */
    static final String DISCOVER_METHOD = "server/discover";

    /** Case-insensitive {@code Bearer } scheme prefix (note the trailing space). */
    private static final String BEARER_PREFIX = "bearer ";

    private final OAuthResourceServerConfig config;
    private final ToolAuthorizationService authorizationService;

    /**
     * Body-buffer cap for the server/discover method check, mirroring
     * {@code McpHeaderValidationFilter}'s bound so an oversized body is never fully
     * materialised by the exemption check (fail closed to the bearer challenge).
     */
    private final int maxValidationBodyBytes;

    /** Jackson 3 mapper for the bounded body inspection (immutable, built once). */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public OAuthResourceServerFilter(OAuthResourceServerConfig config, ToolAuthorizationService authorizationService) {
        this(config, authorizationService, McpHeaderValidationFilter.DEFAULT_MAX_VALIDATION_BODY_BYTES);
    }

    @Autowired
    public OAuthResourceServerFilter(
            OAuthResourceServerConfig config,
            ToolAuthorizationService authorizationService,
            @Value("${mcp.transport.max-validation-body-bytes:1048576}") int maxValidationBodyBytes) {
        this.config = config;
        this.authorizationService = authorizationService;
        this.maxValidationBodyBytes = maxValidationBodyBytes > 0
                ? maxValidationBodyBytes
                : McpHeaderValidationFilter.DEFAULT_MAX_VALIDATION_BODY_BYTES;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpReq) || !(response instanceof HttpServletResponse httpRes)) {
            chain.doFilter(request, response);
            return;
        }

        // Inert unless the operator has opted into resource-server enforcement.
        if (!config.enforcementEnabled() || !isEnforcedPath(httpReq)) {
            chain.doFilter(request, response);
            return;
        }

        // server/discover on the protocol endpoint (POST /mcp) is a pre-auth surface
        // (SEP-2575): a client must negotiate protocol versions before it can obtain a
        // token. Detect it from the JSON-RPC body (bounded buffering, replayed downstream);
        // an unparseable body is not exempt — the enforcement decision falls through to
        // the bearer challenge, keeping non-discover traffic fully authenticated.
        HttpServletRequest effectiveReq = httpReq;
        if ("POST".equalsIgnoreCase(httpReq.getMethod())
                && (MCP_PROTOCOL_PATH.equals(pathOf(httpReq)) || pathOf(httpReq).startsWith(MCP_PATH_PREFIX))) {
            McpHeaderValidationFilter.CachedBodyHttpServletRequest buffered =
                    new McpHeaderValidationFilter.CachedBodyHttpServletRequest(httpReq, maxValidationBodyBytes);
            if (!buffered.exceedsLimit() && isServerDiscoverBody(buffered)) {
                // server/discover: pre-auth (SEP-2575) — a client must negotiate protocol
                // versions before it can obtain a token. Forwarded without a challenge,
                // with the replayable buffered body.
                chain.doFilter(buffered, response);
                return;
            }
            // The inspection consumed the original body stream, so downstream (after a
            // valid token) must receive the replayable buffered wrapper, never the drained
            // original. An oversized body fails closed: it falls through to the bearer
            // challenge rather than being served.
            effectiveReq = buffered;
        }

        String token = bearerToken(httpReq.getHeader("Authorization"));

        if (token == null) {
            // No credentials presented: challenge without an error code (RFC6750 §3).
            challenge(httpReq, httpRes, null, "Bearer access token required");
            return;
        }

        if (!authorizationService.isAccessTokenValid(token)) {
            // A token was presented but is not recognised: invalid_token (RFC6750 §3.1).
            challenge(httpReq, httpRes, "invalid_token", "The access token is invalid or expired");
            return;
        }

        chain.doFilter(effectiveReq, response);
    }

    /**
     * @return {@code true} when the request targets an enforced MCP transport path. The
     *     {@code server/discover} probe is exempt so discovery remains unauthenticated.
     */
    private boolean isEnforcedPath(HttpServletRequest req) {
        String path = pathOf(req);
        if (path == null) {
            return false;
        }
        // The protocol endpoint (POST /mcp) is enforced like /mcp/** (its discover-method
        // exemption is body-based, see isServerDiscoverBody); the probe path stays exempt.
        if (MCP_PROTOCOL_PATH.equals(path)) {
            return true;
        }
        if (!path.startsWith(MCP_PATH_PREFIX)) {
            return false;
        }
        return !DISCOVER_PATH.equals(path);
    }

    /**
     * @return {@code true} when the buffered request's JSON-RPC {@code method} is
     *     {@code server/discover} — the pre-auth surface (SEP-2575): a client must negotiate
     *     protocol versions before it can obtain a token. A body that fails to parse is NOT
     *     exempt (fail closed to the bearer challenge); the buffered wrapper stays replayable
     *     for downstream regardless of the outcome.
     */
    private boolean isServerDiscoverBody(McpHeaderValidationFilter.CachedBodyHttpServletRequest buffered) {
        if (buffered.isBodyEmpty()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(buffered.getInputStream());
            if (root != null && root.isObject()) {
                JsonNode methodNode = root.get("method");
                return methodNode != null && methodNode.isTextual() && DISCOVER_METHOD.equals(methodNode.asText());
            }
        } catch (JacksonException parseFailure) {
            // Unparseable body: not exempt. The challenge response does not need the body.
            return false;
        }
        return false;
    }

    /** Resolves the request path ({@code servletPath}, falling back to {@code requestURI}). */
    private static String pathOf(HttpServletRequest req) {
        String path = req.getServletPath();
        if (path == null || path.isEmpty()) {
            path = req.getRequestURI();
        }
        return path;
    }

    /**
     * Extracts the token from an {@code Authorization} header value, accepting the {@code Bearer}
     * scheme case-insensitively.
     *
     * @param headerValue the raw header value, or {@code null}
     * @return the trimmed token, or {@code null} when absent/blank or not a non-empty Bearer token
     */
    private static String bearerToken(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String trimmed = headerValue.trim();
        if (trimmed.length() <= BEARER_PREFIX.length()
                || !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Writes a {@code 401} response carrying the RFC6750 / RFC9728 {@code WWW-Authenticate} challenge
     * and a small JSON error body.
     *
     * @param req the current request (used to build the {@code resource_metadata} URL)
     * @param res the response to write
     * @param error the OAuth error code (e.g. {@code invalid_token}), or {@code null} when no token
     *     was presented
     * @param description a human-readable explanation
     */
    private void challenge(HttpServletRequest req, HttpServletResponse res, String error, String description)
            throws IOException {
        String metadataUrl = config.metadataUrl(req);
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", buildChallenge(error, description, metadataUrl));
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"error\":\"" + JsonUtils.escapeJson(error == null ? "unauthorized" : error)
                + "\",\"error_description\":\"" + JsonUtils.escapeJson(description)
                + "\",\"resource_metadata\":\"" + JsonUtils.escapeJson(metadataUrl) + "\"}";
        res.getWriter().write(body);
        log.debug(
                "OAuth resource-server challenge issued ({}): {}",
                error == null ? "missing token" : error,
                description);
    }

    /**
     * Builds the {@code WWW-Authenticate} header value. Always advertises {@code resource_metadata}
     * (RFC9728 §5.1); includes {@code error}/{@code error_description} only when a token was
     * presented and rejected (RFC6750 §3). Visible for testing.
     *
     * @param error the OAuth error code, or {@code null}
     * @param description a human-readable explanation
     * @param metadataUrl the absolute Protected Resource Metadata URL
     * @return the header value
     */
    static String buildChallenge(String error, String description, String metadataUrl) {
        StringBuilder sb = new StringBuilder("Bearer ");
        if (error != null && !error.isEmpty()) {
            sb.append("error=\"").append(quoteSafe(error)).append("\", ");
            sb.append("error_description=\"").append(quoteSafe(description)).append("\", ");
        }
        sb.append("resource_metadata=\"").append(quoteSafe(metadataUrl)).append('"');
        return sb.toString();
    }

    /**
     * Strips characters that would break an RFC7235 {@code quoted-string} (backslash and double
     * quote). The inputs are server-controlled error codes, fixed descriptions, and a derived URL,
     * so this is defence in depth rather than untrusted-input handling.
     */
    private static String quoteSafe(String value) {
        return value.replace("\\", "").replace("\"", "");
    }
}
