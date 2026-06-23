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

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpServerIdentity#cacheHints()} — the single source of truth for the MCP RC
 * {@code ttlMs}/{@code cacheScope} caching policy (SEP-2549) advertised on the discovery surfaces.
 */
@DisplayName("McpServerIdentity cache hints (SEP-2549)")
class McpServerIdentityTest {

    private final McpServerIdentity identity = new McpServerIdentity("test-server", "9.9.9");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hintFor(Map<String, Object> hints, String method) {
        return (Map<String, Object>) hints.get(method);
    }

    @Test
    @DisplayName("static catalogue list surfaces are public with the generous catalogue TTL")
    void listSurfacesArePublic() {
        Map<String, Object> hints = identity.cacheHints();

        for (String method : new String[] {
            "tools/list", "prompts/list", "resources/list", "resources/templates/list"
        }) {
            Map<String, Object> hint = hintFor(hints, method);
            assertThat(hint)
                    .as("hint for %s", method)
                    .containsEntry("ttlMs", McpServerIdentity.DEFAULT_CATALOG_TTL_MS)
                    .containsEntry("cacheScope", McpServerIdentity.CACHE_SCOPE_PUBLIC);
        }
    }

    @Test
    @DisplayName("per-project resource reads are private with the shorter read TTL")
    void readSurfaceIsPrivate() {
        Map<String, Object> read = hintFor(identity.cacheHints(), "resources/read");

        assertThat(read)
                .containsEntry("ttlMs", McpServerIdentity.DEFAULT_READ_TTL_MS)
                .containsEntry("cacheScope", McpServerIdentity.CACHE_SCOPE_PRIVATE);
    }

    @Test
    @DisplayName("covers exactly the five CacheableResult methods from the spec")
    void coversAllCacheableResultMethods() {
        assertThat(identity.cacheHints())
                .containsOnlyKeys(
                        "tools/list", "prompts/list", "resources/list", "resources/templates/list", "resources/read");
    }

    @Test
    @DisplayName("returns a fresh map each call so callers cannot mutate shared state")
    void returnsFreshMap() {
        Map<String, Object> first = identity.cacheHints();
        first.put("injected", "value");

        assertThat(identity.cacheHints()).doesNotContainKey("injected");
    }
}
