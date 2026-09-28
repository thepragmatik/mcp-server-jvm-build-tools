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
