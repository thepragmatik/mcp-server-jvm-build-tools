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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests for {@link OAuthResourceServerFilter} — the opt-in OAuth 2.1 resource-server bearer-token
 * enforcement with RFC6750 / RFC9728 {@code WWW-Authenticate} challenges.
 */
@DisplayName("OAuthResourceServerFilter")
class OAuthResourceServerFilterTest {

    /** The built-in development key, valid only when no real keys are configured (non-prod). */
    private static final String VALID_TOKEN = "dev-key-unsafe-do-not-use-in-production";

    private ToolAuthorizationService authService;

    @BeforeEach
    void setUp() {
        // Ensure the in-memory dev key is present (permissive, no production profile) so we have a
        // known-valid token to exercise, and so prior tests cannot leak a production profile in.
        System.clearProperty("buildtools.auth.enabled");
        System.clearProperty("buildtools.auth.mode");
        System.clearProperty("spring.profiles.active");
        System.setProperty("buildtools.api.key.integration", VALID_TOKEN);
        System.setProperty("buildtools.api.key.integration.scopes", "build:read");
        authService = new ToolAuthorizationService();
    }

    @AfterEach
    void clearKey() {
        System.clearProperty("buildtools.api.key.integration");
        System.clearProperty("buildtools.api.key.integration.scopes");
    }

    private OAuthResourceServerFilter filter(boolean enabled) {
        return new OAuthResourceServerFilter(new OAuthResourceServerConfig(enabled, "", List.of()), authService);
    }

    private static MockHttpServletRequest mcpPost(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp/message");
        req.setServletPath("/mcp/message");
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(8080);
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        return req;
    }

    @Nested
    @DisplayName("disabled (default) — fully backward compatible")
    class Disabled {

        @Test
        @DisplayName("passes /mcp requests through with no token and no challenge")
        void passesThroughWhenDisabled() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(false).doFilter(mcpPost(null), res, chain);

            assertThat(chain.getRequest()).as("downstream reached").isNotNull();
            assertThat(res.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(res.getHeader("WWW-Authenticate")).isNull();
        }
    }

    @Nested
    @DisplayName("enabled — enforces bearer tokens on /mcp/**")
    class Enabled {

        @Test
        @DisplayName("default API-key mode challenges without OAuth discovery")
        void missingTokenChallenged() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(true).doFilter(mcpPost(null), res, chain);

            assertThat(chain.getRequest()).as("downstream NOT reached").isNull();
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            String challenge = res.getHeader("WWW-Authenticate");
            assertThat(challenge).isEqualTo("Bearer");
            assertThat(res.getContentAsString()).doesNotContain("resource_metadata");
        }

        @Test
        @DisplayName("configured issuer enables the RFC9728 discovery challenge")
        void issuerChallenged() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            new OAuthResourceServerFilter(
                            new OAuthResourceServerConfig(true, "", List.of("https://as.example.com")), authService)
                    .doFilter(mcpPost(null), res, new MockFilterChain());

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(res.getHeader("WWW-Authenticate"))
                    .contains("resource_metadata=\"http://localhost:8080"
                            + OAuthResourceServerConfig.PROTECTED_RESOURCE_METADATA_PATH + "\"");
        }

        @Test
        @DisplayName("proxied challenge advertises the external metadata URL")
        void proxyChallengeUsesConfiguredResourceOrigin() throws Exception {
            MockHttpServletRequest internal = mcpPost(null);
            internal.setServerName("127.0.0.1");
            internal.addHeader("X-Forwarded-Host", "untrusted.example.net");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new OAuthResourceServerFilter(
                            new OAuthResourceServerConfig(
                                    true, "https://mcp.example.com/mcp", List.of("https://as.example.com")),
                            authService)
                    .doFilter(internal, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getHeader("WWW-Authenticate"))
                    .contains("resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource\"")
                    .doesNotContain("127.0.0.1", "untrusted.example.net");
        }

        @Test
        @DisplayName("invalid token -> 401 with invalid_token error and challenge")
        void invalidTokenChallenged() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(true).doFilter(mcpPost("not-a-real-token"), res, chain);

            assertThat(chain.getRequest()).as("downstream NOT reached").isNull();
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(res.getHeader("WWW-Authenticate"))
                    .contains("error=\"invalid_token\"")
                    .doesNotContain("resource_metadata");
            assertThat(res.getContentAsString()).contains("\"error\":\"invalid_token\"");
        }

        @Test
        @DisplayName("valid token -> passes through")
        void validTokenPassesThrough() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            MockHttpServletRequest req = mcpPost(VALID_TOKEN);
            req.setContent("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"params\":{}}".getBytes());
            filter(true).doFilter(req, res, chain);

            assertThat(chain.getRequest()).as("downstream reached").isNotNull();
            assertThat(res.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        void toolCallRequiresMatchingScope() throws Exception {
            MockHttpServletRequest denied = mcpPost(VALID_TOKEN);
            denied.setContent(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"execute_build_command\",\"arguments\":{}}}"
                            .getBytes());
            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
            MockFilterChain deniedChain = new MockFilterChain();
            filter(true).doFilter(denied, deniedResponse, deniedChain);
            assertThat(deniedResponse.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            assertThat(deniedResponse.getHeader("WWW-Authenticate"))
                    .contains("error=\"insufficient_scope\"")
                    .doesNotContain("resource_metadata");
            assertThat(deniedChain.getRequest()).isNull();

            MockHttpServletRequest allowed = mcpPost(VALID_TOKEN);
            allowed.setContent(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"detect_build_tool\",\"arguments\":{}}}"
                            .getBytes());
            MockFilterChain allowedChain = new MockFilterChain();
            filter(true).doFilter(allowed, new MockHttpServletResponse(), allowedChain);
            assertThat(allowedChain.getRequest()).isNotNull();
        }

        @Test
        @DisplayName("non-/mcp paths are never challenged")
        void nonMcpPathPassesThrough() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
            req.setServletPath("/health");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(true).doFilter(req, res, chain);

            assertThat(chain.getRequest()).as("downstream reached").isNotNull();
            assertThat(res.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }

        @Test
        @DisplayName("server/discover stays open (discovery must precede authentication)")
        void discoverIsExempt() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp/discover");
            req.setServletPath("/mcp/discover");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(true).doFilter(req, res, chain);

            assertThat(chain.getRequest()).as("downstream reached").isNotNull();
            assertThat(res.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("WWW-Authenticate challenge formatting")
    class ChallengeFormat {

        @Test
        void opaqueKeyChallengeHasNoOAuthMetadata() {
            assertThat(OAuthResourceServerFilter.buildChallenge(null, "Bearer access token required", null))
                    .isEqualTo("Bearer");
            assertThat(OAuthResourceServerFilter.buildChallenge("invalid_token", "bad", null))
                    .isEqualTo("Bearer error=\"invalid_token\", error_description=\"bad\"");
        }

        @Test
        @DisplayName("missing-token challenge advertises only resource_metadata")
        void missingTokenChallengeFormat() {
            String challenge =
                    OAuthResourceServerFilter.buildChallenge(null, "Bearer access token required", "http://h/m");
            assertThat(challenge).isEqualTo("Bearer resource_metadata=\"http://h/m\"");
        }

        @Test
        @DisplayName("invalid-token challenge includes error and error_description")
        void invalidTokenChallengeFormat() {
            String challenge = OAuthResourceServerFilter.buildChallenge("invalid_token", "bad", "http://h/m");
            assertThat(challenge)
                    .isEqualTo("Bearer error=\"invalid_token\", error_description=\"bad\", "
                            + "resource_metadata=\"http://h/m\"");
        }
    }
}
