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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
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
        authService = new ToolAuthorizationService();
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
        @DisplayName("missing token -> 401 with resource_metadata challenge, no error code")
        void missingTokenChallenged() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(true).doFilter(mcpPost(null), res, chain);

            assertThat(chain.getRequest()).as("downstream NOT reached").isNull();
            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            String challenge = res.getHeader("WWW-Authenticate");
            assertThat(challenge)
                    .startsWith("Bearer ")
                    .contains("resource_metadata=\"http://localhost:8080"
                            + OAuthResourceServerConfig.PROTECTED_RESOURCE_METADATA_PATH + "\"");
            assertThat(challenge).doesNotContain("error=");
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
                    .contains("resource_metadata=\"");
            assertThat(res.getContentAsString()).contains("\"error\":\"invalid_token\"");
        }

        @Test
        @DisplayName("valid token -> passes through")
        void validTokenPassesThrough() throws Exception {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter(true).doFilter(mcpPost(VALID_TOKEN), res, chain);

            assertThat(chain.getRequest()).as("downstream reached").isNotNull();
            assertThat(res.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
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
