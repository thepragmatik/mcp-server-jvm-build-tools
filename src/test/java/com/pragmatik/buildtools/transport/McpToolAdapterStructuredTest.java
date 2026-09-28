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
package com.pragmatik.buildtools.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pragmatik.buildtools.build.BuildOutputParser;
import com.pragmatik.buildtools.gradle.GradleOutputParser;
import com.pragmatik.buildtools.maven.MavenOutputParser;
import com.pragmatik.buildtools.sbt.SbtOutputParser;
import com.pragmatik.buildtools.security.ModelOutputPolicy;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

class McpToolAdapterStructuredTest {
    private final JsonMapper json = new JsonMapper();
    private final JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);

    @Test
    void advertisesSchemaForBothBuildResultTools() {
        var schema = McpToolAdapter.tool(callback("analyze_build_output", "{}"), mapper)
                .outputSchema();
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsEntry("additionalProperties", false);
        assertThat(schema.get("required")).isEqualTo(java.util.List.of("completed"));
        assertThat(McpToolAdapter.tool(callback("execute_build_command", "{}"), mapper)
                        .outputSchema())
                .isEqualTo(schema);
        assertThat(McpToolAdapter.tool(callback("detect_build_tool", "{}"), mapper)
                        .outputSchema())
                .isNull();
    }

    @Test
    void executionHasOneCallAndEquivalentBoundedStructuredAndTextResults() {
        String raw =
                "[ERROR] /workspace/private/A.java:42: cannot find symbol alice@example.invalid SYNTHETIC_SECRET\nBUILD FAILURE";
        String safe = new ModelOutputPolicy().protect("execute_build_command", raw);
        ToolCallback callback = callback("execute_build_command", safe);
        var result = McpToolAdapter.call(callback, mapper, new CallToolRequest("execute_build_command", Map.of()));
        verify(callback, times(1)).call("{}");
        assertThat(result.isError()).isTrue();
        assertThat(BuildResultSchema.conforms((Map<String, Object>) result.structuredContent()))
                .isTrue();
        assertThat(json.readTree(((TextContent) result.content().getFirst()).text()))
                .isEqualTo(json.valueToTree(result.structuredContent()));
        assertThat(result.structuredContent().toString())
                .doesNotContain("/workspace/private", "alice@example.invalid", "SYNTHETIC_SECRET");
    }

    @Test
    void successfulMavenExecutionUsesOneCallbackAndKeepsTextParity() {
        String safe = new ModelOutputPolicy().protect("execute_build_command", "[INFO] BUILD SUCCESS");
        ToolCallback callback = callback("execute_build_command", safe);
        var result = McpToolAdapter.call(callback, mapper, new CallToolRequest("execute_build_command", Map.of()));
        verify(callback, times(1)).call("{}");
        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(Map.of("completed", true, "success", true));
        assertThat(json.readTree(((TextContent) result.content().getFirst()).text()))
                .isEqualTo(json.valueToTree(result.structuredContent()));
    }

    @Test
    void executionDiagnosticsStayWithinSchemaBounds() {
        String raw = "[ERROR] /workspace/private/A.java:42: cannot find symbol alice@example.invalid SYNTHETIC_SECRET\n"
                        .repeat(30)
                + "BUILD FAILURE";
        String safe = new ModelOutputPolicy().protect("execute_build_command", raw);
        var result = McpToolAdapter.call(
                callback("execute_build_command", safe),
                mapper,
                new CallToolRequest("execute_build_command", Map.of()));
        assertThat(BuildResultSchema.conforms((Map<String, Object>) result.structuredContent()))
                .isTrue();
        assertThat(((java.util.List<?>) ((Map<?, ?>) result.structuredContent()).get("diagnostics")))
                .hasSizeLessThanOrEqualTo(12);
        assertThat(result.structuredContent().toString())
                .doesNotContain("/workspace/private", "alice@example.invalid", "SYNTHETIC_SECRET");
    }

    @Test
    void executionTimeoutExceptionReturnsSafeSchemaConformingObject() {
        ToolCallback callback = callback("execute_build_command", "{}");
        when(callback.call("{}"))
                .thenThrow(
                        new IllegalStateException("timeout /workspace/private/alice@example.invalid SYNTHETIC_SECRET"));
        var result = McpToolAdapter.call(callback, mapper, new CallToolRequest("execute_build_command", Map.of()));
        verify(callback, times(1)).call("{}");
        assertThat(result.isError()).isTrue();
        assertThat(BuildResultSchema.conforms((Map<String, Object>) result.structuredContent()))
                .isTrue();
        assertThat(json.readTree(((TextContent) result.content().getFirst()).text()))
                .isEqualTo(json.valueToTree(result.structuredContent()));
        assertThat(result.structuredContent().toString())
                .doesNotContain("/workspace/private", "alice@example.invalid", "SYNTHETIC_SECRET");
    }

    @Test
    void analysisHasEquivalentStructuredAndTextResultsWithoutPrivateData() {
        String safe = """
                {"completed":true,"success":false,"isError":true,"errorCount":1,
                "diagnostics":[{"severity":"error","category":"compilation","diagnosticRef":"d1",
                "fileRef":"f1","fileType":"java","line":42,"message":"cannot find symbol: class [redacted-symbol]"}]}
                """.trim();
        var result = McpToolAdapter.call(
                callback("analyze_build_output", safe), mapper, new CallToolRequest("analyze_build_output", Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(BuildResultSchema.conforms((Map<String, Object>) result.structuredContent()))
                .isTrue();
        assertThat(json.readTree(((TextContent) result.content().getFirst()).text()))
                .isEqualTo(json.valueToTree(result.structuredContent()));
        assertThat(result.structuredContent().toString())
                .doesNotContain("/workspace/private", "alice@example.invalid", "SYNTHETIC_SECRET");
    }

    @Test
    void isErrorComesFromBooleanFieldRatherThanMessageSubstring() {
        String safe = "{\"completed\":true,\"details\":\"literal \\\"isError\\\":true is harmless\"}";
        var result = McpToolAdapter.call(
                callback("detect_build_tool", safe), mapper, new CallToolRequest("detect_build_tool", Map.of()));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void exceptionProducesSchemaConformingSafeStructuredResult() {
        ToolCallback callback = callback("analyze_build_output", "{}");
        when(callback.call("{}"))
                .thenThrow(new IllegalStateException("/workspace/private/alice@example.invalid SYNTHETIC_SECRET"));
        var result = McpToolAdapter.call(callback, mapper, new CallToolRequest("analyze_build_output", Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent())
                .isEqualTo(Map.of(
                        "completed", true,
                        "isError", true,
                        "details", "Tool execution failed; details withheld by privacy policy"));
        assertThat(BuildResultSchema.conforms((Map<String, Object>) result.structuredContent()))
                .isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .doesNotContain("/workspace/private", "alice@example.invalid", "SYNTHETIC_SECRET");
    }

    @Test
    void outOfContractProjectionFailsClosedWithoutEchoingData() {
        var result = McpToolAdapter.call(
                callback("analyze_build_output", "{\"completed\":true,\"private\":\"SYNTHETIC_SECRET\"}"),
                mapper,
                new CallToolRequest("analyze_build_output", Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent().toString()).doesNotContain("SYNTHETIC_SECRET");
    }

    @Test
    void realBuildParserProjectionsConformForAllSupportedTools() {
        var policy = new ModelOutputPolicy();
        for (BuildOutputParser parser :
                java.util.List.of(new MavenOutputParser(), new GradleOutputParser(), new SbtOutputParser())) {
            var parsed = parser.parse(
                    "BUILD FAILURE\n[ERROR] /workspace/private/A.java:42: cannot find symbol JaneDoe", 1, "test");
            var safe =
                    json.readValue(policy.protect("analyze_build_output", json.writeValueAsString(parsed)), Map.class);
            assertThat(BuildResultSchema.conforms(safe))
                    .as(parser.getToolName())
                    .isTrue();
            assertThat(((Map<?, ?>) safe.get("testSummary"))
                            .keySet().stream().map(Object::toString).toList())
                    .as(parser.getToolName() + " test summary keys")
                    .containsExactlyInAnyOrder("total", "passed", "failed", "errors", "skipped");
            assertThat(safe.toString()).doesNotContain("/workspace/private", "JaneDoe");
        }
    }

    @Test
    void normalizedFailedTestFitsDeclaredCategoryAndBounds() {
        var policy = new ModelOutputPolicy();
        String raw = """
                {"success":false,"testSummary":{"total":1,"passed":0,"failed":1,"errors":0,"skipped":0},
                 "errors":[{"file":"/workspace/private/JaneDoeTest.java","line":9,
                            "message":"AssertionError: SYNTHETIC_SECRET mismatch JaneDoe"}]}
                """;
        var safe = json.readValue(policy.protect("analyze_build_output", raw), Map.class);
        assertThat(BuildResultSchema.conforms(safe)).isTrue();
        assertThat(((Map<?, ?>) ((java.util.List<?>) safe.get("diagnostics")).getFirst()).get("category"))
                .isEqualTo("test");
        assertThat(safe.toString()).doesNotContain("/workspace/private", "JaneDoe", "SYNTHETIC_SECRET");
    }

    @Test
    void cappedMavenSummaryFitsDeclaredNumericBound() {
        var parsed = new MavenOutputParser()
                .parse("[INFO] Tests run: 9999999999999999999999, Failures: 0, Errors: 0, Skipped: 0", 0, "test");
        var safe = json.readValue(
                new ModelOutputPolicy().protect("analyze_build_output", json.writeValueAsString(parsed)), Map.class);
        assertThat(BuildResultSchema.conforms(safe)).isTrue();
        assertThat(((Map<?, ?>) safe.get("testSummary")).get("countsCapped")).isEqualTo(true);
    }

    private static ToolCallback callback(String name, String output) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(ToolDefinition.builder()
                        .name(name)
                        .description("Synthetic fixture")
                        .inputSchema("{\"type\":\"object\"}")
                        .build());
        when(callback.call("{}")).thenReturn(output);
        return callback;
    }
}
