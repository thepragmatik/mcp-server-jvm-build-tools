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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for issuing and validating JWT Bearer access tokens (RFC 9068) used by the Client
 * Credentials Grant token endpoint ({@link OAuthTokenController}).
 *
 * <p>Tokens are signed with a configurable HMAC-SHA256 key (base64-encoded) and carry the standard
 * JWT claims set defined by RFC 9068: {@code iss}, {@code sub}, {@code aud}, {@code exp},
 * {@code iat}, {@code nbf}, {@code jti}, {@code scope}, and {@code client_id}.
 *
 * <p>For development, an ephemeral key is generated at startup with a warning. For production,
 * configure {@code buildtools.oauth.token-endpoint.signing-key} with a securely generated,
 * base64-encoded HMAC key (at least 256 bits).
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /** Default token TTL (1 hour) when not overridden by client registration or property. */
    static final long DEFAULT_TTL_SECONDS = 3600L;

    /** Minimum HMAC key length for HS256: 256 bits = 32 bytes. */
    private static final int MIN_HMAC_KEY_BYTES = 32;

    private final SecretKey signingKey;
    private final String issuer;
    private final long defaultTtlSeconds;
    private final List<String> audiences;

    /**
     * Spring injection point.
     *
     * @param signingKeyEncoded base64-encoded HMAC signing key; if blank/absent, an ephemeral key
     *     is generated at startup (suitable for development only)
     * @param issuer the issuer URL placed in the {@code iss} claim
     * @param defaultTtlSeconds default token TTL in seconds
     * @param audiences the audience(s) for the {@code aud} claim, comma-separated; defaults to the
     *     issuer's base URL + "/mcp" when blank
     */
    public JwtTokenService(
            @Value("${buildtools.oauth.token-endpoint.signing-key:}") String signingKeyEncoded,
            @Value("${buildtools.oauth.token-endpoint.issuer:}") String issuer,
            @Value("${buildtools.oauth.token-endpoint.default-ttl-seconds:" + DEFAULT_TTL_SECONDS + "}")
                    long defaultTtlSeconds,
            @Value("${buildtools.oauth.token-endpoint.audiences:}") String audiences) {
        this.signingKey = resolveSigningKey(signingKeyEncoded);
        this.issuer = issuer != null && !issuer.isBlank() ? issuer : "mcp-server-jvm-build-tools";
        this.defaultTtlSeconds = defaultTtlSeconds > 0 ? defaultTtlSeconds : DEFAULT_TTL_SECONDS;
        this.audiences = parseAudiences(audiences);
    }

    /**
     * Generate a signed JWT Bearer token for the given client and scopes.
     *
     * @param clientId the authenticated client's identifier
     * @param scopes the granted scopes (space-separated in the {@code scope} claim)
     * @param ttlSeconds token TTL in seconds; if zero or negative, the default TTL is used
     * @return a signed JWT string (the {@code access_token})
     */
    public String generateToken(String clientId, List<String> scopes, long ttlSeconds) {
        long effectiveTtl = ttlSeconds > 0 ? ttlSeconds : defaultTtlSeconds;
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(effectiveTtl);

        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(clientId)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", scopes))
                .claim("client_id", clientId)
                .claim("token_type", "Bearer");

        if (!audiences.isEmpty()) {
            claimsBuilder.audience((java.util.List<String>) audiences);
        }

        JWTClaimsSet claimsSet = claimsBuilder.build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        try {
            signedJWT.sign(new MACSigner(signingKey));
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to sign JWT access token", e);
        }

        return signedJWT.serialize();
    }

    /**
     * Validate a JWT Bearer token and return its claims.
     *
     * @param token the JWT string to validate
     * @return a map of claims if the token is valid, or {@code null} if validation fails
     */
    public Map<String, Object> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);

            // Verify signature
            MACVerifier verifier = new MACVerifier(signingKey);
            if (!signedJWT.verify(verifier)) {
                log.debug("JWT signature verification failed");
                return null;
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // Check expiration
            Date expirationTime = claims.getExpirationTime();
            if (expirationTime != null && expirationTime.before(new Date())) {
                log.debug("JWT has expired: exp={}", expirationTime);
                return null;
            }

            // Check not-before
            Date notBeforeTime = claims.getNotBeforeTime();
            if (notBeforeTime != null && notBeforeTime.after(new Date())) {
                log.debug("JWT is not yet valid: nbf={}", notBeforeTime);
                return null;
            }

            // Extract and return claims
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("iss", claims.getIssuer());
            result.put("sub", claims.getSubject());
            result.put("exp", claims.getExpirationTime());
            result.put("iat", claims.getIssueTime());
            result.put("jti", claims.getJWTID());
            result.put("client_id", claims.getStringClaim("client_id"));
            result.put("scope", claims.getStringClaim("scope"));
            result.put("token_type", claims.getStringClaim("token_type"));
            result.put("aud", claims.getAudience());

            return result;
        } catch (ParseException | JOSEException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @return the configured issuer URL
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * @return the default token TTL in seconds
     */
    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    // ── private helpers ──────────────────────────────────────────────────

    private static SecretKey resolveSigningKey(String encodedKey) {
        if (encodedKey != null && !encodedKey.isBlank()) {
            try {
                byte[] keyBytes = Base64.getDecoder().decode(encodedKey.trim());
                if (keyBytes.length < MIN_HMAC_KEY_BYTES) {
                    log.warn(
                            "Configured HMAC signing key is too short ({} bytes, need ≥ {}). "
                                    + "An ephemeral key will be generated instead.",
                            keyBytes.length,
                            MIN_HMAC_KEY_BYTES);
                    return generateEphemeralKey();
                }
                return new SecretKeySpec(keyBytes, "HmacSHA256");
            } catch (IllegalArgumentException e) {
                log.warn("Configured HMAC signing key is not valid base64. "
                        + "An ephemeral key will be generated instead.");
                return generateEphemeralKey();
            }
        }
        log.warn("No HMAC signing key configured. "
                + "An ephemeral key is generated for development. "
                + "Set buildtools.oauth.token-endpoint.signing-key for production.");
        return generateEphemeralKey();
    }

    private static SecretKey generateEphemeralKey() {
        byte[] keyBytes = new byte[MIN_HMAC_KEY_BYTES];
        new java.security.SecureRandom().nextBytes(keyBytes);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    private static List<String> parseAudiences(String audiences) {
        if (audiences == null || audiences.isBlank()) {
            return List.of();
        }
        return List.of(audiences.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
