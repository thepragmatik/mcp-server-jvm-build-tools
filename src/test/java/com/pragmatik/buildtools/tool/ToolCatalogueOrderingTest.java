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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.pragmatik.buildtools.application.BuildToolsApplication;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that the application-wired {@link ToolCallbackProvider} bean returns the real tool
 * catalogue in a stable, deterministic (name-sorted) order — satisfying the MCP RC recommendation
 * that {@code tools/list} be deterministic to enable client/gateway caching (SEP-2549).
 */
@SpringBootTest(classes = BuildToolsApplication.class)
@DisplayName("Tool catalogue deterministic ordering (SEP-2549)")
class ToolCatalogueOrderingTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    private static List<String> names(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).toList();
    }

    @Test
    @DisplayName("the wired catalogue is sorted by tool name")
    void catalogueIsSortedByName() {
        List<String> names = names(toolCallbackProvider.getToolCallbacks());

        assertThat(names).as("expected a non-empty tool catalogue").isNotEmpty();
        assertThat(names)
                .as("tools/list must be returned in deterministic, name-sorted order")
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    @DisplayName("repeated list calls return an identical order")
    void repeatedCallsAreStable() {
        List<String> first = names(toolCallbackProvider.getToolCallbacks());
        List<String> second = names(toolCallbackProvider.getToolCallbacks());

        assertThat(second).isEqualTo(first);
    }
}
