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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragmatik.buildtools.application.BuildToolsApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = BuildToolsApplication.class)
class ToolScopeCoverageTest {
    private static final Path REFERENCE = Path.of("docs/reference/tool-catalog.md");

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

    @Test
    void buildToolMetadataMatchesStructuredDiagnosticContract() {
        for (var callback : callbacks.getToolCallbacks()) {
            String name = callback.getToolDefinition().name();
            if ("execute_build_command".equals(name) || "analyze_build_output".equals(name)) {
                String description = callback.getToolDefinition().description();
                assertTrue(description.contains("12 structured"));
                assertTrue(description.contains("severity"));
                assertTrue(description.contains("category"));
                assertTrue(description.contains("not raw logs"));
            }
        }
    }

    @Test
    void publishedCatalogMatchesTheWiredPublicCatalog() throws IOException {
        List<ToolCallback> tools = Arrays.asList(callbacks.getToolCallbacks());
        assertThat(tools).hasSize(24);
        String rendered = render(tools);
        if (Boolean.getBoolean("toolCatalog.update")) {
            Files.writeString(REFERENCE, rendered, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(REFERENCE, StandardCharsets.UTF_8))
                .as("Regenerate with ./mvnw -Dtest=ToolScopeCoverageTest -DtoolCatalog.update=true test")
                .isEqualTo(rendered);
    }

    private static String render(List<ToolCallback> tools) {
        StringBuilder page = new StringBuilder("""
                # Current MCP tool catalog

                This is the public tool catalog exposed by `tools/list` in the 2.0 development
                line. It is generated from the application-wired tool callback provider, using
                the same safe descriptions sent to MCP clients. The scope column comes from
                `ToolPermission`. A future tool change must update this page deliberately;
                `mvn verify` checks it against the running application.

                The server currently exposes **%d tools**. Path-bearing calls require an
                allowed project root. HTTP `tools/call` requests require an authorized
                bearer key with the listed scope. See the [quickstart](../user-guide/quickstart-v2.md)
                and [2.0 security design](design-v2.md) before granting execution access.

                | Tool | Required scope | Public result contract |
                |------|----------------|------------------------|
                """.formatted(tools.size()));
        for (ToolCallback callback : tools) {
            var definition = callback.getToolDefinition();
            var scopes = Arrays.stream(ToolPermission.values())
                    .filter(permission -> permission.toolNames().contains(definition.name()))
                    .map(ToolPermission::scope)
                    .toList();
            assertThat(scopes)
                    .as("Exactly one public scope for " + definition.name())
                    .hasSize(1);
            page.append("| `")
                    .append(definition.name())
                    .append("` | `")
                    .append(scopes.getFirst())
                    .append("` | ")
                    .append(escapeCell(definition.description()))
                    .append(" |\n");
        }
        page.append("""

                For exact input parameters and JSON schemas, ask the running server for
                `tools/list`; that response is authoritative for the version you installed.
                In builds after `v2.0.0-rc.1`, `analyze_build_output` also advertises an
                `outputSchema` and returns the same safe JSON object in `structuredContent`
                and legacy text; see
                [structured build results](structured-build-results.md).
                Results are bounded and privacy-filtered. Raw build logs and commands stay local;
                diagnostics include at most 12 structured, redacted entries. The older
                [1.x tool reference](tools.md) is retained as an archive and does not describe
                the current public surface.
                """);
        return page.toString();
    }

    private static String escapeCell(String value) {
        return value.replace("|", "&#124;").replace("\r", " ").replace("\n", " ");
    }
}
