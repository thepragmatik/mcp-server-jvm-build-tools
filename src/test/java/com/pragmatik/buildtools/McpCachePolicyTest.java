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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpCachePolicy} — the single source of truth for the MCP RC {@code ttlMs} +
 * {@code cacheScope} cacheability hints (SEP-2549).
 */
@DisplayName("McpCachePolicy (SEP-2549 ttlMs/cacheScope)")
class McpCachePolicyTest {

    @Nested
    @DisplayName("static catalogue surfaces are publicly cacheable for one hour")
    class CatalogueSurfaces {

        @Test
        @DisplayName("tools/list")
        void toolsList() {
            assertCache(McpCachePolicy.toolsList(), McpCachePolicy.CATALOGUE_TTL_MS, McpCachePolicy.PUBLIC);
        }

        @Test
        @DisplayName("prompts/list")
        void promptsList() {
            assertCache(McpCachePolicy.promptsList(), McpCachePolicy.CATALOGUE_TTL_MS, McpCachePolicy.PUBLIC);
        }

        @Test
        @DisplayName("resources/templates/list")
        void resourceTemplatesList() {
            assertCache(
                    McpCachePolicy.resourceTemplatesList(), McpCachePolicy.CATALOGUE_TTL_MS, McpCachePolicy.PUBLIC);
        }
    }

    @Nested
    @DisplayName("per-project surfaces are privately cacheable for one minute")
    class PerProjectSurfaces {

        @Test
        @DisplayName("resources/list")
        void resourcesList() {
            assertCache(McpCachePolicy.resourcesList(), McpCachePolicy.PER_PROJECT_TTL_MS, McpCachePolicy.PRIVATE);
        }

        @Test
        @DisplayName("resources/read")
        void resourcesRead() {
            assertCache(McpCachePolicy.resourcesRead(), McpCachePolicy.PER_PROJECT_TTL_MS, McpCachePolicy.PRIVATE);
        }
    }

    @Test
    @DisplayName("the catalogue TTL is longer than the per-project TTL")
    void catalogueTtlIsLongerThanPerProject() {
        assertThat(McpCachePolicy.CATALOGUE_TTL_MS).isGreaterThan(McpCachePolicy.PER_PROJECT_TTL_MS);
        assertThat(McpCachePolicy.CATALOGUE_TTL_MS).isEqualTo(3_600_000L);
        assertThat(McpCachePolicy.PER_PROJECT_TTL_MS).isEqualTo(60_000L);
        assertThat(McpCachePolicy.PUBLIC).isEqualTo("public");
        assertThat(McpCachePolicy.PRIVATE).isEqualTo("private");
    }

    @Nested
    @DisplayName("cache(ttlMs, cacheScope) factory")
    class CacheFactory {

        @Test
        @DisplayName("produces a fragment with exactly the two required fields")
        void producesTwoFields() {
            Map<String, Object> cache = McpCachePolicy.cache(123L, McpCachePolicy.PUBLIC);
            assertThat(cache).containsOnlyKeys("ttlMs", "cacheScope");
            assertThat(cache).containsEntry("ttlMs", 123L).containsEntry("cacheScope", "public");
        }

        @Test
        @DisplayName("a zero TTL (never cache) is permitted")
        void zeroTtlAllowed() {
            assertThat(McpCachePolicy.cache(0L, McpCachePolicy.PRIVATE)).containsEntry("ttlMs", 0L);
        }

        @Test
        @DisplayName("rejects a negative TTL")
        void rejectsNegativeTtl() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> McpCachePolicy.cache(-1L, McpCachePolicy.PUBLIC))
                    .withMessageContaining("ttlMs");
        }

        @Test
        @DisplayName("rejects a cacheScope other than public/private")
        void rejectsUnknownScope() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> McpCachePolicy.cache(1L, "shared"))
                    .withMessageContaining("cacheScope");
            assertThatIllegalArgumentException().isThrownBy(() -> McpCachePolicy.cache(1L, null));
        }
    }

    @Test
    @DisplayName("advertisedPolicy maps every CacheableResult surface to its fragment")
    @SuppressWarnings("unchecked")
    void advertisedPolicyCoversEverySurface() {
        Map<String, Object> policy = McpCachePolicy.advertisedPolicy();

        assertThat(policy)
                .containsOnlyKeys(
                        "tools/list", "prompts/list", "resources/list", "resources/read", "resources/templates/list");

        assertCache(
                (Map<String, Object>) policy.get("tools/list"), McpCachePolicy.CATALOGUE_TTL_MS, McpCachePolicy.PUBLIC);
        assertCache(
                (Map<String, Object>) policy.get("prompts/list"),
                McpCachePolicy.CATALOGUE_TTL_MS,
                McpCachePolicy.PUBLIC);
        assertCache(
                (Map<String, Object>) policy.get("resources/templates/list"),
                McpCachePolicy.CATALOGUE_TTL_MS,
                McpCachePolicy.PUBLIC);
        assertCache(
                (Map<String, Object>) policy.get("resources/list"),
                McpCachePolicy.PER_PROJECT_TTL_MS,
                McpCachePolicy.PRIVATE);
        assertCache(
                (Map<String, Object>) policy.get("resources/read"),
                McpCachePolicy.PER_PROJECT_TTL_MS,
                McpCachePolicy.PRIVATE);
    }

    @Test
    @DisplayName("each call returns a fresh, independently mutable map (no shared state)")
    void returnsFreshMaps() {
        Map<String, Object> first = McpCachePolicy.toolsList();
        first.put("injected", true);
        Map<String, Object> second = McpCachePolicy.toolsList();
        assertThat(second).doesNotContainKey("injected");

        Map<String, Object> policyA = McpCachePolicy.advertisedPolicy();
        policyA.clear();
        assertThat(McpCachePolicy.advertisedPolicy()).isNotEmpty();
    }

    private static void assertCache(Map<String, Object> cache, long expectedTtl, String expectedScope) {
        assertThat(cache).containsEntry("ttlMs", expectedTtl).containsEntry("cacheScope", expectedScope);
    }
}
