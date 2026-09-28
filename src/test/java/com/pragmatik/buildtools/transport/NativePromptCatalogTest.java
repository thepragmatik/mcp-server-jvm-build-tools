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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativePromptCatalogTest {
    private final NativePromptCatalog catalog = new NativePromptCatalog();

    @Test
    void catalogIsStaticAndContainsNoArguments() {
        assertThat(catalog.prompts())
                .hasSize(3)
                .allSatisfy(prompt -> assertThat(prompt.arguments()).isEmpty());
        assertThatThrownBy(() -> catalog.prompts().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getRejectsUnrecognizedArgumentsWithoutEchoingThem() {
        String canary = "private-canary@example.invalid";
        assertThatThrownBy(() ->
                        catalog.getPrompt(new GetPromptRequest("diagnose_build_failure", Map.of("projectDir", canary))))
                .isInstanceOf(McpError.class)
                .hasMessageNotContaining(canary);
    }

    @Test
    void getReturnsOnlyServerAuthoredText() {
        var result = catalog.getPrompt(new GetPromptRequest("plan_test_strategy", Map.of()));
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().content().toString()).contains("narrowest safe tests");
    }
}
