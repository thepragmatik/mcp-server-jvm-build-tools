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
package com.pragmatik.buildtools.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.1 Client Credentials Grant token endpoint (RFC 6749 §4.4).
 *
 * <p>Accepts client credentials via {@code Authorization: Basic} (client_secret_basic) or via
 * form parameters {@code client_id} + {@code client_secret} (client_secret_post), validates
 * them against the registered clients in {@link OAuthClientRegistrationRepository}, and issues
 * a short-lived JWT Bearer access token signed by {@link JwtTokenService}.
 *
 * <p>The endpoint is served at {@code POST /oauth/token} and is intentionally outside the
 * {@code /mcp/**} path prefix so it bypasses the existing {@code OAuthResourceServerFilter}.
 *
 * <h2>Error responses (RFC 6749 §5.2)</h2>
 * <ul>
 *   <li>{@code 400} {@code invalid_request} — missing or malformed parameters</li>
 *   <li>{@code 400} {@code unsupported_grant_type} — grant_type other than {@code client_credentials}</li>
 *   <li>{@code 400} {@code invalid_scope} — requested scope exceeds the client's registered scopes</li>
 *   <li>{@code 401} {@code invalid_client} — unknown or unauthenticated client</li>
 * </ul>
 */
@RestController
public class OAuthTokenController {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenController.class);

    /** The only supported grant type for this endpoint. */
    static final String CLIENT_CREDENTIALS_GRANT_TYPE = "client_credentials";

    /** Expected token type in responses. */
    static final String BEARER_TOKEN_TYPE = "Bearer";

    /** Case-insensitive Basic auth scheme prefix. */
    private static final String BASIC_PREFIX = "basic ";

    private final JwtTokenService jwtTokenService;
    private final OAuthClientRegistrationRepository clientRepository;

    public OAuthTokenController(JwtTokenService jwtTokenService, OAuthClientRegistrationRepository clientRepository) {
        this.jwtTokenService = jwtTokenService;
        this.clientRepository = clientRepository;
    }

    /**
     * Issue an access token via the Client Credentials grant.
     *
     * @param authorization the {@code Authorization} header value (for {@code client_secret_basic})
     * @param grantType the {@code grant_type} parameter (required)
     * @param scope the requested {@code scope} parameter (optional)
     * @param clientIdParam the {@code client_id} parameter (for {@code client_secret_post})
     * @param clientSecretParam the {@code client_secret} parameter (for {@code client_secret_post})
     * @return a JSON response with the access token, or an OAuth error JSON body
     */
    @PostMapping(
            value = "/oauth/token",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> tokenEndpoint(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "grant_type", required = false) String grantType,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "client_id", required = false) String clientIdParam,
            @RequestParam(name = "client_secret", required = false) String clientSecretParam) {

        // ── 1. Validate grant_type ──────────────────────────────────────
        if (grantType == null || grantType.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_request", "Missing required parameter: grant_type");
        }
        if (!CLIENT_CREDENTIALS_GRANT_TYPE.equals(grantType)) {
            return errorResponse(
                    HttpStatus.BAD_REQUEST,
                    "unsupported_grant_type",
                    "Authorization server does not support grant type: " + grantType);
        }

        // ── 2. Extract client credentials ───────────────────────────────
        String clientId;
        String clientSecret;

        // Try Authorization: Basic first
        String[] basicCredentials = extractBasicCredentials(authorization);
        if (basicCredentials != null) {
            clientId = basicCredentials[0];
            clientSecret = basicCredentials[1];
        } else if (clientIdParam != null && !clientIdParam.isBlank()) {
            // Fall back to form parameters (client_secret_post, OAuth 2.1 discouraged but supported
            // for backward compatibility with simpler CI/CD workflows)
            clientId = clientIdParam;
            clientSecret = clientSecretParam != null ? clientSecretParam : "";
        } else {
            return errorResponse(
                    HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed: no credentials provided");
        }

        // ── 3. Look up and authenticate client ──────────────────────────
        Optional<OAuthClientRegistration> clientOpt = clientRepository.findByClientId(clientId);
        if (clientOpt.isEmpty()) {
            log.warn("Token request for unknown client_id: {}", clientId);
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed");
        }

        OAuthClientRegistration client = clientOpt.get();

        // Validate client_secret (for client_secret_basic and client_secret_post) using
        // constant-time comparison to prevent timing side-channel attacks (CWE-208)
        if (client.clientSecret() != null
                && !client.clientSecret().isEmpty()
                && !MessageDigest.isEqual(
                        client.clientSecret().getBytes(StandardCharsets.UTF_8),
                        clientSecret.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Token request with invalid secret for client: {}", clientId);
            return errorResponse(HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed");
        }

        // ── 4. Validate scopes ──────────────────────────────────────────
        List<String> requestedScopes = parseScopeParameter(scope);
        List<String> grantedScopes;

        if (requestedScopes.isEmpty()) {
            // No scopes requested: grant all registered scopes
            grantedScopes = client.scopes();
        } else {
            // Requested scopes MUST be a subset of the client's registered scopes
            List<String> invalidScopes = new ArrayList<>();
            for (String requested : requestedScopes) {
                if (!client.scopes().contains(requested)) {
                    invalidScopes.add(requested);
                }
            }
            if (!invalidScopes.isEmpty()) {
                return errorResponse(
                        HttpStatus.BAD_REQUEST,
                        "invalid_scope",
                        "Requested scope(s) are not allowed: " + String.join(", ", invalidScopes) + ". Valid scopes: "
                                + String.join(", ", client.scopes()));
            }
            grantedScopes = requestedScopes;
        }

        // ── 5. Issue token ──────────────────────────────────────────────
        long ttl = client.tokenTtlSeconds();
        String accessToken = jwtTokenService.generateToken(clientId, grantedScopes, ttl);

        log.info("Issued access token for client: {} (scopes={}, ttl={}s)", clientId, grantedScopes, ttl);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", BEARER_TOKEN_TYPE);
        response.put("expires_in", ttl);
        response.put("scope", String.join(" ", grantedScopes));

        return ResponseEntity.ok(response);
    }

    // ── private helpers ──────────────────────────────────────────────────

    /**
     * Extract client_id:client_secret from an Authorization: Basic header.
     *
     * @param authorization the raw header value
     * @return a two-element String array {@code [clientId, clientSecret]}, or {@code null} if the
     *     header is missing, not Basic auth, or unparseable
     */
    private static String[] extractBasicCredentials(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String trimmed = authorization.trim();
        if (trimmed.length() <= BASIC_PREFIX.length()
                || !trimmed.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return null;
        }
        String encoded = trimmed.substring(BASIC_PREFIX.length()).trim();
        if (encoded.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded));
            int colonIndex = decoded.indexOf(':');
            if (colonIndex < 0) {
                return null;
            }
            return new String[] {decoded.substring(0, colonIndex), decoded.substring(colonIndex + 1)};
        } catch (IllegalArgumentException e) {
            log.debug("Failed to decode Basic auth header: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the {@code scope} parameter into a list of individual scopes. Scopes are
     * space-delimited per RFC 6749 §3.3.
     *
     * @param scope the raw scope parameter value, or {@code null}
     * @return a (possibly empty) list of trimmed, non-empty scope strings
     */
    private static List<String> parseScopeParameter(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String s : scope.split(" ")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Build an OAuth error response (RFC 6749 §5.2).
     *
     * @param status the HTTP status code
     * @param error the OAuth error code
     * @param description a human-readable error description
     * @return a {@link ResponseEntity} with the error body
     */
    private static ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status, String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return ResponseEntity.status(status).body(body);
    }
}
