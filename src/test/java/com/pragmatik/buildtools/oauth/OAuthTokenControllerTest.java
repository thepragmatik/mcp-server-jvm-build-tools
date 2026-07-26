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

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Integration tests for the OAuth 2.1 Client Credentials Grant token endpoint ({@link
 * OAuthTokenController}).
 *
 * <p>Uses {@link RestTemplate} against the running server to exercise the full HTTP request /
 * response cycle including JWT signing and validation.
 */
@SpringBootTest(
        classes = com.pragmatik.buildtools.application.BuildToolsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.web-application-type=servlet",
            "buildtools.oauth.token-endpoint.enabled=true",
            "buildtools.oauth.token-endpoint.issuer=https://test-issuer.example.com",
            // Fixed 256-bit HMAC key for reproducible JWT verification in tests:
            // AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=
            "buildtools.oauth.token-endpoint.signing-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            "buildtools.oauth.token-endpoint.default-ttl-seconds=3600",
            // Register a test client with multiple scopes
            "buildtools.oauth.clients[0].client-id=test-client",
            "buildtools.oauth.clients[0].client-secret=test-secret",
            "buildtools.oauth.clients[0].scopes=build:read,build:execute,dependency:read",
            "buildtools.oauth.clients[0].grant-types=client_credentials",
            "buildtools.oauth.clients[0].token-ttl-seconds=3600",
            // Test client with a single scope and custom TTL
            "buildtools.oauth.clients[1].client-id=readonly-client",
            "buildtools.oauth.clients[1].client-secret=readonly-secret",
            "buildtools.oauth.clients[1].scopes=dependency:read",
            "buildtools.oauth.clients[1].grant-types=client_credentials",
            "buildtools.oauth.clients[1].token-ttl-seconds=900",
        })
@DisplayName("OAuthTokenController — Client Credentials Grant")
class OAuthTokenControllerTest {

    /**
     * Base64-encoded fixed 256-bit HMAC key matching
     * {@code AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=}.
     * Bytes: 0x00-0x1f sequentially.
     */
    private static final byte[] TEST_KEY_BYTES = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
    };

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    private String tokenUrl() {
        return "http://localhost:" + port + "/oauth/token";
    }

    // ── Happy path ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("happy path — client_secret_basic")
    class HappyPathBasicAuth {

        @Test
        @DisplayName("valid credentials with all scopes -> 200 with JWT access token")
        void validTokenIssuance() {
            HttpHeaders headers = basicAuthHeaders("test-client", "test-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", null);

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertValidJwtResponse(
                    response.getBody(), "test-client", 3600, List.of("build:read", "build:execute", "dependency:read"));
        }

        @Test
        @DisplayName("request specific subset of scopes -> 200 with only requested scopes")
        void tokenWithSubsetScopes() {
            HttpHeaders headers = basicAuthHeaders("test-client", "test-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", "build:read");

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertValidJwtResponse(response.getBody(), "test-client", 3600, List.of("build:read"));
        }
    }

    @Nested
    @DisplayName("happy path — client_secret_post (form params)")
    class HappyPathFormAuth {

        @Test
        @DisplayName("valid credentials via form params -> 200 with JWT")
        void tokenViaFormParams() {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("client_id", "test-client");
            body.add("client_secret", "test-secret");
            body.add("scope", "build:read");

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, formHeaders()), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertValidJwtResponse(response.getBody(), "test-client", 3600, List.of("build:read"));
        }
    }

    @Nested
    @DisplayName("happy path — readonly client")
    class HappyPathReadonly {

        @Test
        @DisplayName("readonly client with custom TTL -> 200 with correct expires_in")
        void tokenWithCustomTtl() {
            HttpHeaders headers = basicAuthHeaders("readonly-client", "readonly-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", "dependency:read");

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertValidJwtResponse(response.getBody(), "readonly-client", 900, List.of("dependency:read"));
        }
    }

    // ── Error cases ────────────────────────────────────────────────────

    @Nested
    @DisplayName("error — invalid_request")
    class ErrorInvalidRequest {

        @Test
        @DisplayName("missing grant_type -> 400 invalid_request")
        void missingGrantType() {
            HttpHeaders headers = basicAuthHeaders("test-client", "test-secret");
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("scope", "build:read");

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                Map bodyMap = e.getResponseBodyAs(Map.class);
                assertThat(bodyMap).containsEntry("error", "invalid_request");
            }
        }

        @Test
        @DisplayName("unsupported grant_type -> 400 unsupported_grant_type")
        void unsupportedGrantType() {
            HttpHeaders headers = basicAuthHeaders("test-client", "test-secret");
            MultiValueMap<String, String> body = formBody("authorization_code", null);

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                Map bodyMap = e.getResponseBodyAs(Map.class);
                assertThat(bodyMap).containsEntry("error", "unsupported_grant_type");
            }
        }
    }

    @Nested
    @DisplayName("error — invalid_client")
    class ErrorInvalidClient {

        @Test
        @DisplayName("unknown client_id -> 401 invalid_client")
        void unknownClient() {
            HttpHeaders headers = basicAuthHeaders("unknown-client", "some-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", null);

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }

        @Test
        @DisplayName("wrong client_secret -> 401 invalid_client")
        void wrongSecret() {
            HttpHeaders headers = basicAuthHeaders("test-client", "wrong-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", null);

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }

        @Test
        @DisplayName("no credentials at all -> 401 invalid_client")
        void noCredentials() {
            MultiValueMap<String, String> body = formBody("client_credentials", null);

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, formHeaders()), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }
    }

    @Nested
    @DisplayName("error — invalid_scope")
    class ErrorInvalidScope {

        @Test
        @DisplayName("requested scope not in registered scopes -> 400 invalid_scope")
        void scopeNotRegistered() {
            HttpHeaders headers = basicAuthHeaders("test-client", "test-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", "admin:wildcard");

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                Map bodyMap = e.getResponseBodyAs(Map.class);
                assertThat(bodyMap).containsEntry("error", "invalid_scope");
                String description = (String) bodyMap.get("error_description");
                assertThat(description).contains("admin:wildcard");
            }
        }

        @Test
        @DisplayName("all requested scopes exceed registered scopes -> 400 invalid_scope")
        void allScopesExceed() {
            HttpHeaders headers = basicAuthHeaders("readonly-client", "readonly-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", "build:execute credential:read");

            try {
                restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                Map bodyMap = e.getResponseBodyAs(Map.class);
                assertThat(bodyMap).containsEntry("error", "invalid_scope");
            }
        }
    }

    // ── JWT verification ───────────────────────────────────────────────

    @Nested
    @DisplayName("JWT token validation")
    class JwtValidation {

        @Test
        @DisplayName("issued JWT can be decoded and verified with the same key")
        void jwtIsValidatable() throws Exception {
            HttpHeaders headers = basicAuthHeaders("test-client", "test-secret");
            MultiValueMap<String, String> body = formBody("client_credentials", "build:read");

            ResponseEntity<Map> response =
                    restTemplate.postForEntity(tokenUrl(), new HttpEntity<>(body, headers), Map.class);

            String accessToken = (String) response.getBody().get("access_token");

            // Parse and verify the JWT
            SignedJWT signedJWT = SignedJWT.parse(accessToken);
            JWSVerifier verifier = new MACVerifier(new SecretKeySpec(TEST_KEY_BYTES, "HmacSHA256"));
            assertThat(signedJWT.verify(verifier)).isTrue();

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            assertThat(claims.getIssuer()).isEqualTo("https://test-issuer.example.com");
            assertThat(claims.getSubject()).isEqualTo("test-client");
            assertThat(claims.getStringClaim("client_id")).isEqualTo("test-client");
            assertThat(claims.getStringClaim("scope")).isEqualTo("build:read");
            assertThat(claims.getStringClaim("token_type")).isEqualTo("Bearer");

            // Check expiration is in the future
            assertThat(claims.getExpirationTime()).isAfter(new Date());
            // Check iat is in the past (or very recent)
            assertThat(claims.getIssueTime()).isBeforeOrEqualTo(new Date());

            // jti should be present
            assertThat(claims.getJWTID()).isNotNull().isNotEmpty();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static HttpHeaders basicAuthHeaders(String clientId, String clientSecret) {
        HttpHeaders headers = new HttpHeaders();
        String encoded = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    private static HttpHeaders formHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    private static MultiValueMap<String, String> formBody(String grantType, String scope) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", grantType);
        if (scope != null) {
            body.add("scope", scope);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void assertValidJwtResponse(
            Map<String, Object> response, String expectedClientId, int expectedExpiresIn, List<String> expectedScopes) {

        assertThat(response).isNotNull();
        assertThat(response).containsKey("access_token");
        assertThat(response.get("access_token")).isInstanceOf(String.class);
        assertThat((String) response.get("access_token")).isNotEmpty();

        assertThat(response).containsEntry("token_type", "Bearer");
        assertThat(response).containsEntry("expires_in", expectedExpiresIn);

        // Scope assertion
        String scopeStr = (String) response.get("scope");
        assertThat(scopeStr).isNotNull();
        List<String> actualScopes = List.of(scopeStr.split(" "));
        assertThat(actualScopes).containsExactlyInAnyOrderElementsOf(expectedScopes);
    }
}
