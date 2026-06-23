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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests for {@link OAuthProtectedResourceMetadataController} — the RFC9728 Protected Resource
 * Metadata document served at {@code /.well-known/oauth-protected-resource}.
 */
@DisplayName("OAuthProtectedResourceMetadataController")
class OAuthProtectedResourceMetadataControllerTest {

    private final McpServerIdentity identity = new McpServerIdentity("test-server", "9.9.9");

    private OAuthProtectedResourceMetadataController controller(String resource, List<String> authorizationServers) {
        OAuthResourceServerConfig config = new OAuthResourceServerConfig(false, resource, authorizationServers);
        return new OAuthProtectedResourceMetadataController(config, identity);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("localhost");
        req.setServerPort(8080);
        return req;
    }

    @Test
    @DisplayName("derives the resource identifier and core fields from the request")
    void coreFields() {
        Map<String, Object> metadata = controller("", List.of()).protectedResourceMetadata(request());

        assertThat(metadata).containsEntry("resource", "http://localhost:8080/mcp");
        assertThat(metadata).containsEntry("resource_name", "test-server");
        assertThat(metadata).containsEntry("bearer_methods_supported", List.of("header"));
        assertThat(metadata).containsKey("resource_documentation");
    }

    @Test
    @DisplayName("scopes_supported lists the ToolPermission scopes and never offline_access")
    @SuppressWarnings("unchecked")
    void scopesSupported() {
        Map<String, Object> metadata = controller("", List.of()).protectedResourceMetadata(request());

        List<String> scopes = (List<String>) metadata.get("scopes_supported");
        assertThat(scopes)
                .contains("build:read", "dependency:read")
                .doesNotContain("offline_access")
                .doesNotContain("*");
    }

    @Test
    @DisplayName("omits authorization_servers when none are configured")
    void omitsAuthorizationServersWhenEmpty() {
        Map<String, Object> metadata = controller("", List.of()).protectedResourceMetadata(request());
        assertThat(metadata).doesNotContainKey("authorization_servers");
    }

    @Test
    @DisplayName("emits authorization_servers when configured")
    void emitsAuthorizationServersWhenConfigured() {
        Map<String, Object> metadata =
                controller("", List.of("https://as.example.com")).protectedResourceMetadata(request());
        assertThat(metadata).containsEntry("authorization_servers", List.of("https://as.example.com"));
    }

    @Test
    @DisplayName("honours an explicitly configured resource identifier")
    void honoursConfiguredResource() {
        Map<String, Object> metadata =
                controller("https://mcp.example.com/mcp", List.of()).protectedResourceMetadata(request());
        assertThat(metadata).containsEntry("resource", "https://mcp.example.com/mcp");
    }
}
