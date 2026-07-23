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
package com.pragmatik.buildtools.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.build.BuildToolProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for CVE/security features added to DependencyService in v1.1.0 (F2).
 */
@DisplayName("DependencyService security features (F2)")
class DependencyServiceSecurityTest {

    private final DependencyService service = new DependencyService(new BuildToolProvider());

    // ── Security enrichment (internal) ──────────────────────────────

    @Nested
    @DisplayName("enrichWithSecurityInfo")
    class EnrichWithSecurityInfo {

        @Test
        @DisplayName("adds security field to result")
        void addsSecurityField() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("groupId", "com.example");
            result.put("artifactId", "test-lib");

            service.enrichWithSecurityInfo(result, "com.example", "test-lib", "1.0.0");

            assertThat(result).containsKey("security");
            @SuppressWarnings("unchecked")
            Map<String, Object> security = (Map<String, Object>) result.get("security");
            assertThat(security).containsKeys("cveCount", "highestSeverity", "vulnerabilities");
        }

        @Test
        @DisplayName("handles network errors gracefully (returns partial result with warning)")
        void handlesNetworkErrorsGracefully() {
            Map<String, Object> result = new LinkedHashMap<>();
            // Use a non-existent host to trigger network error
            service.enrichWithSecurityInfo(result, "com.nonexistent.dep", "fake-artifact", "999.0.0");

            assertThat(result).containsKey("security");
            @SuppressWarnings("unchecked")
            Map<String, Object> security = (Map<String, Object>) result.get("security");
            // Should still have the basic fields even on error
            assertThat(security).containsKey("cveCount");
            assertThat(security).containsKey("highestSeverity");
        }
    }

    // ── scanDependencyCves ──────────────────────────────────────────

    @Nested
    @DisplayName("scanDependencyCves")
    class ScanDependencyCves {

        @Test
        @DisplayName("returns error for missing projectDir")
        void returnsErrorForMissingProjectDir() {
            String json = service.scanDependencyCves(null, null);
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("returns error for nonexistent path")
        void returnsErrorForNonexistentPath() {
            String json = service.scanDependencyCves("/nonexistent/path", null);
            assertThat(json).contains("error");
        }

        @Test
        @DisplayName("returns error for directory without build files")
        void returnsErrorForNoBuildFiles() throws Exception {
            String tmpdir = System.getProperty("java.io.tmpdir");
            String json = service.scanDependencyCves(tmpdir, null);
            assertThat(json).contains("error");
            assertThat(json).contains("No build files");
        }
    }
}
