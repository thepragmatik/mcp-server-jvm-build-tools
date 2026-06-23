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
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Tests for streamable HTTP transport configuration, CORS,
 * request logging, and SSE event broadcasting.
 */
class TransportConfigTest {

    private static final String MCP_MAPPING = "/mcp/**";

    /**
     * Applies the {@link TransportConfig#corsConfigurer()} mapping to a real
     * {@link CorsRegistry} and returns the resulting {@link CorsConfiguration}
     * for the {@code /mcp/**} path so tests can assert what is actually enforced.
     */
    @SuppressWarnings("unchecked")
    private static CorsConfiguration corsConfigFor(String allowedOrigins) {
        TransportConfig config = new TransportConfig();
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", allowedOrigins);
        WebMvcConfigurer configurer = config.corsConfigurer();
        CorsRegistry registry = new CorsRegistry();
        configurer.addCorsMappings(registry);
        Map<String, CorsConfiguration> configs =
                (Map<String, CorsConfiguration>) ReflectionTestUtils.invokeMethod(registry, "getCorsConfigurations");
        assertNotNull(configs);
        CorsConfiguration cors = configs.get(MCP_MAPPING);
        assertNotNull(cors, "expected a CORS configuration for " + MCP_MAPPING);
        return cors;
    }

    @Test
    void transportConfig_beanExists() {
        TransportConfig config = new TransportConfig();
        assertNotNull(config.corsConfigurer());
    }

    @Test
    void corsConfigurer_defaultRestrictsToLocalOriginsWithoutWildcard() {
        TransportConfig config = new TransportConfig();
        // The shipped default must never be a wildcard.
        assertFalse(config.usesWildcard(), "default CORS must not use a wildcard");
        assertThat(config.parsedAllowedOrigins()).containsExactly("http://localhost:8080", "http://127.0.0.1:8080");

        CorsConfiguration cors = corsConfigFor("http://localhost:8080,http://127.0.0.1:8080");
        assertThat(cors.getAllowedOrigins())
                .containsExactly("http://localhost:8080", "http://127.0.0.1:8080")
                .doesNotContain("*");
        // Exact origins must NOT be registered as patterns.
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getAllowCredentials()).isTrue();
        assertThat(cors.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "OPTIONS");
    }

    @Test
    void corsConfigurer_customExactOriginsUseAllowedOrigins() {
        CorsConfiguration cors = corsConfigFor("https://dashboard.example.com");
        assertThat(cors.getAllowedOrigins()).containsExactly("https://dashboard.example.com");
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    @Test
    void corsConfigurer_wildcardIsHonouredViaAllowedOriginPatterns() {
        // A wildcard cannot be combined with allowCredentials via allowedOrigins,
        // so it must be applied through allowedOriginPatterns (opt-in, dev only).
        CorsConfiguration cors = corsConfigFor("*");
        assertThat(cors.getAllowedOriginPatterns()).containsExactly("*");
        assertThat(cors.getAllowedOrigins()).isNullOrEmpty();
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    @Test
    void corsConfigurer_subdomainWildcardUsesPatterns() {
        CorsConfiguration cors = corsConfigFor("https://*.example.com");
        assertThat(cors.getAllowedOriginPatterns()).containsExactly("https://*.example.com");
        assertThat(cors.getAllowedOrigins()).isNullOrEmpty();
    }

    @Test
    void corsConfigurer_blankOriginsDenyAllCrossOrigin() {
        // No configured origins => empty allowedOrigins (no cross-origin permitted).
        CorsConfiguration cors = corsConfigFor("");
        assertThat(cors.getAllowedOrigins()).isNullOrEmpty();
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
    }

    @Test
    void parsedAllowedOrigins_trimsAndFiltersBlankEntries() {
        TransportConfig config = new TransportConfig();
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "  https://a.test , , https://b.test  ");
        assertThat(config.parsedAllowedOrigins()).containsExactly("https://a.test", "https://b.test");
        assertFalse(config.usesWildcard());
    }

    @Test
    void parsedAllowedOrigins_emptyWhenNullOrBlank() {
        TransportConfig config = new TransportConfig();
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "   ");
        assertThat(config.parsedAllowedOrigins()).isEmpty();
        assertFalse(config.usesWildcard());

        ReflectionTestUtils.setField(config, "corsAllowedOrigins", null);
        assertThat(config.parsedAllowedOrigins()).isEmpty();
        assertFalse(config.usesWildcard());
    }

    @Test
    void buildEventController_initialSubscriberCount() {
        BuildEventController controller = new BuildEventController();
        String result = controller.subscriberCount();
        assertTrue(result.contains("\"subscribers\":0"));
        assertTrue(result.contains("/mcp/build-events/stream"));
    }

    @Test
    void buildEventController_createsSseEmitter() {
        BuildEventController controller = new BuildEventController();
        var emitter = controller.stream();
        assertNotNull(emitter);
        assertEquals(30 * 60 * 1000L, emitter.getTimeout());
    }

    @Test
    void buildEventController_broadcastToNone() {
        BuildEventController controller = new BuildEventController();
        // Broadcasting with no subscribers should not throw
        assertDoesNotThrow(() -> controller.broadcast("build-start", "{\"status\":\"started\"}"));
    }

    @Test
    void transportLoggingFilter_logsWithoutError() throws Exception {
        TransportLoggingFilter filter = new TransportLoggingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        assertDoesNotThrow(() -> filter.doFilter(req, res, chain));
    }
}
