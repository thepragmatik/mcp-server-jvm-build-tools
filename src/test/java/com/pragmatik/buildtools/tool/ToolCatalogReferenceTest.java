package com.pragmatik.buildtools.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.application.BuildToolsApplication;
import com.pragmatik.buildtools.security.ToolPermission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Checks the published tool reference against the callback provider used by the MCP transports.
 *
 * <p>Regenerate deliberately with {@code ./mvnw -Dtest=ToolCatalogReferenceTest
 * -DtoolCatalog.update=true test}; normal tests never write to the checkout.
 */
@SpringBootTest(classes = BuildToolsApplication.class)
class ToolCatalogReferenceTest {
    private static final Path REFERENCE = Path.of("docs/reference/tool-catalog.md");

    @Autowired
    private ToolCallbackProvider callbacks;

    @Test
    void publishedCatalogMatchesTheWiredPublicCatalog() throws IOException {
        List<ToolCallback> tools = Arrays.asList(callbacks.getToolCallbacks());
        assertThat(tools).hasSize(24);
        String rendered = render(tools);
        if (Boolean.getBoolean("toolCatalog.update")) {
            Files.writeString(REFERENCE, rendered, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(REFERENCE, StandardCharsets.UTF_8))
                .as("Regenerate with ./mvnw -Dtest=ToolCatalogReferenceTest -DtoolCatalog.update=true test")
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
