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
package com.pragmatik.buildtools.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.application.BuildToolsApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression tests for issue #189: the {@code full} summary mode must group the real tool
 * catalogue into per-service buckets at runtime, not collapse everything into a single
 * {@code ungrouped} key.
 *
 * <p>These tests run the full application context, where {@code ToolMetricsAspect} proxies every
 * tool service bean — the exact condition under which {@code ToolCatalogueSummary} previously
 * scanned the CGLIB proxy class instead of the user class, producing an empty
 * {@code serviceByToolName} map. They therefore reproduce the production wiring end-to-end,
 * unlike the plain unit fixtures in {@code McpDiscoverControllerTest} (which use un-proxied fake
 * tool beans).
 */
@SpringBootTest(classes = BuildToolsApplication.class)
@DisplayName("Tool catalogue service grouping (issue #189)")
class ToolCatalogueGroupingTest {

    @Autowired
    private ToolCatalogueSummary toolCatalogueSummary;

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> fullModeGroups() {
        Map<String, Object> summary = toolCatalogueSummary.summary();
        assertThat(summary).containsKeys("count", "names", "groups");
        return (Map<String, List<String>>) summary.get("groups");
    }

    @Test
    @DisplayName("full mode groups the real catalogue into at least two service buckets")
    void fullModeYieldsMultipleServiceBuckets() {
        Map<String, List<String>> groups = fullModeGroups();

        assertThat(groups)
                .as("expected non-trivial groups, not a single 'ungrouped' bucket")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(groups)
                .as("no tool should be ungrouped when every registered service is scanned")
                .doesNotContainKey(ToolCatalogueSummary.UNGROUPED);

        int groupedTotal = groups.values().stream().mapToInt(List::size).sum();
        int nameTotal = ((List<String>) toolCatalogueSummary.summary().get("names")).size();
        assertThat(groupedTotal)
                .as("every catalogued tool must appear in exactly one group")
                .isEqualTo(nameTotal);
    }

    @Test
    @DisplayName("full mode summary output is byte-identical across repeated calls")
    void fullModeIsDeterministicAcrossCalls() {
        Map<String, Object> first = toolCatalogueSummary.summary();
        Map<String, Object> second = toolCatalogueSummary.summary();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("each group key is a real service class name, not a proxy class name")
    void groupKeysAreRealServiceNames() {
        Map<String, List<String>> groups = fullModeGroups();

        assertThat(groups.keySet())
                .as("CGLIB proxy class names carry a '$$' marker and must never leak into group keys")
                .noneMatch(key -> key.contains("$$"));
    }
}
