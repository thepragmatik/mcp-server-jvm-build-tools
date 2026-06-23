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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests for {@link OAuthResourceServerConfig} — the single source of truth for this server's
 * OAuth 2.1 resource-server profile (RFC9728 metadata + RFC6750 challenges).
 */
@DisplayName("OAuthResourceServerConfig")
class OAuthResourceServerConfigTest {

    @Nested
    @DisplayName("scopes_supported")
    class ScopesSupported {

        @Test
        @DisplayName("advertises the fine-grained ToolPermission scopes")
        void advertisesToolPermissionScopes() {
            OAuthResourceServerConfig config = new OAuthResourceServerConfig(false, "", List.of());
            assertThat(config.scopesSupported())
                    .contains("build:read", "build:execute", "dependency:read", "resource:read")
                    .containsExactlyInAnyOrderElementsOf(ToolPermission.allScopes());
        }

        @Test
        @DisplayName("never advertises offline_access or the wildcard scope")
        void neverAdvertisesOfflineAccessOrWildcard() {
            OAuthResourceServerConfig config = new OAuthResourceServerConfig(false, "", List.of());
            assertThat(config.scopesSupported())
                    .doesNotContain(OAuthResourceServerConfig.OFFLINE_ACCESS_SCOPE)
                    .doesNotContain(OAuthResourceServerConfig.WILDCARD_SCOPE);
        }
    }

    @Nested
    @DisplayName("resource identifier")
    class ResourceIdentifier {

        @Test
        @DisplayName("uses the configured resource verbatim when set")
        void usesConfiguredResource() {
            OAuthResourceServerConfig config =
                    new OAuthResourceServerConfig(false, "https://mcp.example.com/mcp", List.of());
            assertThat(config.resourceIdentifier(request("https", "host", 8080)))
                    .isEqualTo("https://mcp.example.com/mcp");
        }

        @Test
        @DisplayName("derives <base>/mcp from the request when unset")
        void derivesFromRequest() {
            OAuthResourceServerConfig config = new OAuthResourceServerConfig(false, "", List.of());
            assertThat(config.resourceIdentifier(request("http", "localhost", 8080)))
                    .isEqualTo("http://localhost:8080/mcp");
        }
    }

    @Nested
    @DisplayName("base URL derivation")
    class BaseUrl {

        @Test
        @DisplayName("includes a non-default port")
        void includesNonDefaultPort() {
            assertThat(OAuthResourceServerConfig.baseUrl(request("http", "localhost", 8080)))
                    .isEqualTo("http://localhost:8080");
        }

        @Test
        @DisplayName("omits the default http port 80")
        void omitsDefaultHttpPort() {
            assertThat(OAuthResourceServerConfig.baseUrl(request("http", "example.com", 80)))
                    .isEqualTo("http://example.com");
        }

        @Test
        @DisplayName("omits the default https port 443")
        void omitsDefaultHttpsPort() {
            assertThat(OAuthResourceServerConfig.baseUrl(request("https", "example.com", 443)))
                    .isEqualTo("https://example.com");
        }
    }

    @Nested
    @DisplayName("metadata URL + authorization servers")
    class MetadataAndAuthServers {

        @Test
        @DisplayName("metadata URL points at the well-known path")
        void metadataUrlPointsAtWellKnown() {
            OAuthResourceServerConfig config = new OAuthResourceServerConfig(false, "", List.of());
            assertThat(config.metadataUrl(request("http", "localhost", 8080)))
                    .isEqualTo("http://localhost:8080" + OAuthResourceServerConfig.PROTECTED_RESOURCE_METADATA_PATH);
        }

        @Test
        @DisplayName("authorization servers parse from a CSV string, trimming blanks")
        void parsesAuthorizationServersCsv() {
            OAuthResourceServerConfig config =
                    new OAuthResourceServerConfig(false, "", " https://as.example.com , , https://as2.example.com ");
            assertThat(config.authorizationServers())
                    .containsExactly("https://as.example.com", "https://as2.example.com");
        }

        @Test
        @DisplayName("enforcement flag reflects the constructor argument")
        void enforcementFlag() {
            assertThat(new OAuthResourceServerConfig(true, "", List.of()).enforcementEnabled())
                    .isTrue();
            assertThat(new OAuthResourceServerConfig(false, "", List.of()).enforcementEnabled())
                    .isFalse();
        }
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setScheme(scheme);
        req.setServerName(host);
        req.setServerPort(port);
        return req;
    }
}
