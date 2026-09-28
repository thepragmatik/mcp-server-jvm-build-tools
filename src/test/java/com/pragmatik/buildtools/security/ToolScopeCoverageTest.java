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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragmatik.buildtools.application.BuildToolsApplication;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = BuildToolsApplication.class)
class ToolScopeCoverageTest {
    @Autowired
    ToolCallbackProvider callbacks;

    @Test
    void registryMatchesRuntimeCatalog() throws Exception {
        var registry = new JsonMapper().readTree(Path.of("mcp-registry.json").toFile());
        List<String> expected = new ArrayList<>();
        for (var name : registry.get("tools").get("names")) {
            expected.add(name.asText());
        }
        var actual = Arrays.stream(callbacks.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .sorted()
                .toList();
        assertEquals(actual, expected);
        assertEquals(actual.size(), registry.get("tools").get("total").intValue());
    }

    @Test
    void everyExposedToolHasAnExplicitPermission() {
        var unknown = Arrays.stream(callbacks.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .filter(name -> !ToolPermission.isKnownTool(name))
                .toList();
        assertTrue(unknown.isEmpty(), "Unscoped MCP tools: " + unknown);
    }

    @Test
    void dependencyToolMetadataMatchesAggregateResultContract() {
        var descriptions = Arrays.stream(callbacks.getToolCallbacks())
                .collect(java.util.stream.Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        callback -> callback.getToolDefinition().description()));
        assertTrue(descriptions.get("analyze_pom_dependencies").contains("counts"));
        assertTrue(descriptions.get("analyze_pom_dependencies").contains("withheld"));
        assertTrue(descriptions.get("scan_dependency_cves").contains("counts"));
        assertTrue(descriptions.get("scan_dependency_cves").contains("withheld"));
    }
}
